package RISCV

import chisel3._
import chisel3.util._
import _root_.circt.stage.ChiselStage

class QueueEntry extends Bundle {
    val instruction = new InstructionBundle()
    val valid = Bool()
}

class InstructionDispatchQueue() extends Module {
    val io = IO(new Bundle {
        val instruction = Input(new InstructionBundle())
        val valid = Input(Bool())

        val jump_unit_out = Output(new InstructionBundle())
        val jump_unit_out_valid = Output(Bool())
        val jump_unit_ready = Input(Bool())

        val alu_out = Output(new InstructionBundle())
        val alu_out_valid = Output(Bool())
        val alu_ready = Input(Bool())

        val malu_out = Output(new InstructionBundle())
        val malu_out_valid = Output(Bool())
        val malu_ready = Input(Bool())

        val lsu_out = Output(new InstructionBundle())
        val lsu_out_valid = Output(Bool())
        val lsu_ready = Input(Bool())

        val reorder_buffer_tail = Input(UInt(8.W))

        val broadcast_free_valid = Input(Bool())
        val broadcast_free_register = Input(UInt(5.W))
        val broadcast_free_value = Input(UInt(32.W))

        val flush = Input(Bool())

        val ready = Output(Bool())

        val debug = Input(Bool())
    })

    val queue = RegInit(VecInit(Seq.fill(8)(0.U.asTypeOf(new QueueEntry))))
    val dependence_size = RegInit(VecInit(Seq.fill(32)(0.U(32.W))))

    val full = queue(7.U).valid

    // Dispatch
    def compare_entry(low: Int, high: Int): (UInt, Bool) = {
        val index = Wire(UInt(3.W))
        val valid = Wire(Bool())

        if (high == low) {
            val queueEntry = queue(low.U)

            index := low.U
            valid := queueEntry.valid &&
                queueEntry.instruction.rs1_dependence_counter === 0.U &&
                queueEntry.instruction.rs2_dependence_counter === 0.U &&
                queueEntry.instruction.rd_dependence_counter === 0.U &&
                (
                  (io.alu_ready && queueEntry.instruction.pe_type === PeType.Alu)||(io.malu_ready && queueEntry.instruction.pe_type === PeType.Malu) ||
                      (io.lsu_ready && queueEntry.instruction.pe_type === PeType.Lsu && queueEntry.instruction.reorder_pointer === io.reorder_buffer_tail) ||
                      (io.jump_unit_ready && queueEntry.instruction.pe_type === PeType.JumpUnit && queueEntry.instruction.reorder_pointer === io.reorder_buffer_tail)
                )
        } else {
            val mid = low + (high - low) / 2
            val lowResult = compare_entry(low, mid)
            val highResult = compare_entry(mid + 1, high)

            when(lowResult._2) {
                index := lowResult._1
                valid := lowResult._2
            }.otherwise {
                index := highResult._1
                valid := highResult._2
            }
        }

        return (index, valid)
    }

    val first_valid_result = compare_entry(0, 7)
    val first_valid_entry = first_valid_result._1
    val first_valid_entry_valid = first_valid_result._2

    // Broadcast Free
    when(io.broadcast_free_valid && dependence_size(io.broadcast_free_register) > 0.U) {
        dependence_size(io.broadcast_free_register) := dependence_size(io.broadcast_free_register) - 1.U

        for (n <- 0 to 7) {
            when(queue(n.U).instruction.rs1 === io.broadcast_free_register && queue(n.U).instruction.rs1_dependence_counter > 0.U) {
                queue(n.U).instruction.rs1_value := io.broadcast_free_value
                queue(n.U).instruction.rs1_dependence_counter := queue(n.U).instruction.rs1_dependence_counter - 1.U
            }

            when(queue(n.U).instruction.rs2 === io.broadcast_free_register && queue(n.U).instruction.rs2_dependence_counter > 0.U) {
                queue(n.U).instruction.rs2_value := io.broadcast_free_value
                queue(n.U).instruction.rs2_dependence_counter := queue(n.U).instruction.rs2_dependence_counter - 1.U
            }

            when(queue(n.U).instruction.rd === io.broadcast_free_register && queue(n.U).instruction.rd_dependence_counter > 0.U) {
                queue(n.U).instruction.rd_dependence_counter := queue(n.U).instruction.rd_dependence_counter - 1.U
            }
        }
    }

    // Assign PE
    io.jump_unit_out := queue(first_valid_entry).instruction
    io.jump_unit_out_valid := first_valid_entry_valid && queue(first_valid_entry).instruction.pe_type === PeType.JumpUnit

    io.lsu_out := queue(first_valid_entry).instruction
    io.lsu_out_valid := first_valid_entry_valid && queue(first_valid_entry).instruction.pe_type === PeType.Lsu

    io.alu_out := queue(first_valid_entry).instruction
    io.alu_out_valid := first_valid_entry_valid && queue(first_valid_entry).instruction.pe_type === PeType.Alu

    io.malu_out := queue(first_valid_entry).instruction
    io.malu_out_valid := first_valid_entry_valid && queue(first_valid_entry).instruction.pe_type === PeType.Malu

    when(first_valid_entry_valid) {
        queue(first_valid_entry).valid := false.B
    }

    // Shifting Instructions
    for (n <- 1 to 7) {
        when(!queue((n - 1).U).valid) {
            queue((n - 1).U) := queue(n.U)
            queue(n.U).valid := false.B

            when(
              io.broadcast_free_valid && queue(n.U).instruction.rs1 === io.broadcast_free_register && queue(
                n.U
              ).instruction.rs1_dependence_counter > 0.U
            ) {
                queue((n - 1).U).instruction.rs1_value := io.broadcast_free_value
                queue((n - 1).U).instruction.rs1_dependence_counter := queue(n.U).instruction.rs1_dependence_counter - 1.U
            }

            when(
              io.broadcast_free_valid && queue(n.U).instruction.rs2 === io.broadcast_free_register && queue(
                n.U
              ).instruction.rs2_dependence_counter > 0.U
            ) {
                queue((n - 1).U).instruction.rs2_value := io.broadcast_free_value
                queue((n - 1).U).instruction.rs2_dependence_counter := queue(n.U).instruction.rs2_dependence_counter - 1.U
            }

            when(
              io.broadcast_free_valid && queue(n.U).instruction.rd === io.broadcast_free_register && queue(
                n.U
              ).instruction.rd_dependence_counter > 0.U
            ) {
                queue((n - 1).U).instruction.rd_dependence_counter := queue(n.U).instruction.rd_dependence_counter - 1.U
            }

            when(first_valid_entry_valid && first_valid_entry === n.U) {
                queue((n - 1).U).valid := false.B
            }
        }
    }

    io.ready := !full

    // Inserting Instruction
    when(io.valid && !full) {
        when(io.instruction.write_mode === WriteMode.Register) {
            dependence_size(io.instruction.rd(4, 0)) := dependence_size(io.instruction.rd(4, 0)) + 1.U

            when(io.broadcast_free_valid && io.instruction.rd === io.broadcast_free_register) {
                dependence_size(io.instruction.rd(4, 0)) := dependence_size(io.instruction.rd(4, 0))
            }
        }

        queue(7.U).instruction := io.instruction
        queue(7.U).valid := true.B

        queue(7.U).instruction.rs1_dependence_counter := dependence_size(io.instruction.rs1)
        queue(7.U).instruction.rs2_dependence_counter := dependence_size(io.instruction.rs2)
        queue(7.U).instruction.rd_dependence_counter := dependence_size(io.instruction.rd(4, 0))

        when(io.broadcast_free_valid && io.broadcast_free_register === io.instruction.rs1) {
            queue(7.U).instruction.rs1_value := io.broadcast_free_value
            queue(7.U).instruction.rs1_dependence_counter := dependence_size(io.broadcast_free_register) - 1.U
        }

        when(io.broadcast_free_valid && io.broadcast_free_register === io.instruction.rs2) {
            queue(7.U).instruction.rs2_value := io.broadcast_free_value
            queue(7.U).instruction.rs2_dependence_counter := dependence_size(io.broadcast_free_register) - 1.U
        }

        when(io.broadcast_free_valid && io.broadcast_free_register === io.instruction.rd(4, 0)) {
            queue(7.U).instruction.rd_dependence_counter := dependence_size(io.broadcast_free_register) - 1.U
        }
    }

    // Flushing
    when(io.flush) {
        for (n <- 0 to 31) {
            dependence_size(n.U) := 0.U
        }

        for (n <- 0 to 7) {
            queue(n.U) := 0.U.asTypeOf(new QueueEntry)
        }
    }

    when(io.debug) {
        for (n <- 0 to 7) {
            when(queue(n.U).valid) {
                printf(
                  "%d -> opcode: %b rp: %d ip: %d rd: %d %d rs1: %d %d %d rs2: %d %d %d \n",
                  n.U,
                  queue(n.U).instruction.opcode,
                  queue(n.U).instruction.reorder_pointer,
                  queue(n.U).instruction.instruction_pointer,
                  queue(n.U).instruction.rd,
                  queue(n.U).instruction.rd_dependence_counter,
                  queue(n.U).instruction.rs1,
                  queue(n.U).instruction.rs1_value,
                  queue(n.U).instruction.rs1_dependence_counter,
                  queue(n.U).instruction.rs2,
                  queue(n.U).instruction.rs2_value,
                  queue(n.U).instruction.rs2_dependence_counter
                )
            }.otherwise {
                printf("%d -> \n", n.U)
            }
        }

        // for (n <- 0 to 31) {
        //     printf(
        //         "Register %d Dependency Size: %d\n",
        //         n.U,
        //         dependence_size(n.U)
        //     )
        // }

        when(io.jump_unit_out_valid) {
            printf(
              "Dispatching to jump unit! op: %b rp: %d ip: %d\n",
              io.jump_unit_out.opcode,
              io.jump_unit_out.reorder_pointer,
              io.jump_unit_out.instruction_pointer
            )
        }

        when(io.lsu_out_valid) {
            printf(
              "Dispatching to lsu! op: %b rp: %d ip: %d\n",
              io.lsu_out.opcode,
              io.lsu_out.reorder_pointer,
              io.lsu_out.instruction_pointer
            )
        }

        when(io.alu_out_valid) {
            printf(
              "Dispatching to alu! op: %b rp: %d ip: %d\n",
              io.alu_out.opcode,
              io.alu_out.reorder_pointer,
              io.alu_out.instruction_pointer
            )
        }

        when(io.broadcast_free_valid) {
            printf(
              "Register freed! %d %d\n",
              io.broadcast_free_register,
              io.broadcast_free_value
            )
        }
    }
}
