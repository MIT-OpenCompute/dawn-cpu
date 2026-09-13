package RISCV
import chisel3._
import chisel3.util._
import _root_.circt.stage.ChiselStage
import scala.math._

object WriteMode extends ChiselEnum {
    val None, Register, Memory = Value
}

object PeType extends ChiselEnum {
    val Alu, Lsu, JumpUnit, Malu = Value
}

class InstructionBundle extends Bundle {
    val rs1 = UInt(5.W)
    val rs1_value = UInt(32.W)
    val rs1_dependence_counter = UInt(32.W)
    val rs2 = UInt(5.W)
    val rs2_value = UInt(32.W)
    val rs2_dependence_counter = UInt(32.W)
    val rd = UInt(32.W)
    val rd_value = UInt(32.W)
    val rd_dependence_counter = UInt(32.W)
    val immediate = UInt(32.W)
    val opcode = UInt(7.W)
    val func3 = UInt(3.W)
    val func7 = UInt(7.W)
    val reorder_pointer = UInt(8.W)
    val write_mode = WriteMode()
    val instruction_pointer = UInt(32.W)
    val predicted_instruction_pointer = UInt(32.W)
    val pe_type = PeType()
    val inst = UInt(32.W)
}

class DecodeStage() extends Module {
    val io = IO(new Bundle {
        val next_ready = Input(Bool())

        val instruction = Input(UInt(32.W))
        val instruction_pointer = Input(UInt(32.W))
        val predicted_instruction_pointer = Input(UInt(32.W))
        val valid = Input(Bool())

        val reorder_buffer_head = Input(UInt(8.W))

        val next_instruction = Output(new InstructionBundle())
        val next_valid = Output(Bool())

        val flush = Input(Bool())

        val ready = Output(Bool())
    })

    val decoder = Module(new Decoder())
    decoder.io.instruction := io.instruction

    val rs1 = RegInit(0.U(5.W))
    val rs2 = RegInit(0.U(5.W))
    val rd = RegInit(0.U(5.W))
    val immediate = RegInit(0.U(32.W))
    val opcode = RegInit(0.U(7.W))
    val func3 = RegInit(0.U(3.W))
    val func7 = RegInit(0.U(7.W))
    val instruction_pointer = RegInit(0.U(32.W))
    val predicted_instruction_pointer = RegInit(0.U(32.W))
    val write_mode = RegInit(WriteMode.None)
    val pe_type = RegInit(PeType.Alu)
    val valid = RegInit(false.B)

    when(io.next_ready) {
        immediate := decoder.io.immediate
        rs1 := decoder.io.rs1
        rs2 := decoder.io.rs2
        rd := decoder.io.rd
        opcode := decoder.io.opcode
        func3 := decoder.io.func3
        func7 := decoder.io.func7
        instruction_pointer := io.instruction_pointer
        predicted_instruction_pointer := io.predicted_instruction_pointer
        valid := io.valid && !io.flush
    }

    io.ready := io.next_ready
    io.next_instruction.rs1 := rs1
    io.next_instruction.rs1_value := 0.U
    io.next_instruction.rs1_dependence_counter := 0.U
    io.next_instruction.rs2 := rs2
    io.next_instruction.rs2_value := 0.U
    io.next_instruction.rs2_dependence_counter := 0.U
    io.next_instruction.rd := rd
    io.next_instruction.rd_value := 0.U
    io.next_instruction.rd_dependence_counter := 0.U
    io.next_instruction.immediate := immediate
    io.next_instruction.opcode := opcode
    io.next_instruction.func3 := func3
    io.next_instruction.func7 := func7
    io.next_instruction.predicted_instruction_pointer := predicted_instruction_pointer
    io.next_instruction.reorder_pointer := io.reorder_buffer_head
    io.next_instruction.write_mode := write_mode
    io.next_instruction.instruction_pointer := instruction_pointer
    io.next_instruction.pe_type := pe_type
    io.next_instruction.inst :=RegNext(io.instruction)
    io.next_valid := valid

    when(io.next_ready) {
        write_mode := WriteMode.None
        pe_type := PeType.Alu

        switch(decoder.io.opcode) {
            is("b0110111".U) { // LUI
                pe_type := PeType.Alu
                write_mode := WriteMode.Register

                when(decoder.io.rd === 0.U) {
                    write_mode := WriteMode.None
                }
            }

            is("b0010111".U) { // AUIPC
                pe_type := PeType.Alu
                write_mode := WriteMode.Register

                when(decoder.io.rd === 0.U) {
                    write_mode := WriteMode.None
                }
            }

            is("b0010011".U) { // IMMEDIATE MATH
                pe_type := PeType.Alu
                write_mode := WriteMode.Register

                when(decoder.io.rd === 0.U) {
                    write_mode := WriteMode.None
                }
            }

            is("b0110011".U) { // REGISTER MATH
                pe_type := PeType.Alu
                when(decoder.io.func7 === "b0000001".U){
                    pe_type := PeType.Malu
                }
                write_mode := WriteMode.Register

                when(decoder.io.rd === 0.U) {
                    write_mode := WriteMode.None
                }
            }

            is("b0000011".U) { // LOAD
                pe_type := PeType.Lsu
                write_mode := WriteMode.Register

                when(decoder.io.rd === 0.U) {
                    write_mode := WriteMode.None
                }
            }

            is("b0100011".U) { // STORE
                pe_type := PeType.Lsu
                write_mode := WriteMode.Memory
            }

            is("b1101111".U) { // JAL
                pe_type := PeType.JumpUnit
                write_mode := WriteMode.Register
                
                when(decoder.io.rd === 0.U) {
                    write_mode := WriteMode.None
                }
            }

            is("b1100111".U) { // JALR
                pe_type := PeType.JumpUnit
                write_mode := WriteMode.Register

                when(decoder.io.rd === 0.U) {
                    write_mode := WriteMode.None
                }
            }

            is("b1100011".U) { // BRANCH
                pe_type := PeType.JumpUnit
                write_mode := WriteMode.None
            }
        }
    }

    when(io.flush) {
        rs1 := 0.U(5.W)
        rs2 := 0.U(5.W)
        rd := 0.U(5.W)
        immediate := 0.U(32.W)
        opcode := 0.U(7.W)
        func3 := 0.U(3.W)
        func7 := 0.U(7.W)
        instruction_pointer := 0.U(32.W)
        write_mode := WriteMode.None
        pe_type := PeType.Alu
        valid := false.B
    }
}