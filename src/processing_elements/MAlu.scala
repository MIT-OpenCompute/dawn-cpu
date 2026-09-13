package RISCV
import chisel3._
import chisel3.util._
import _root_.circt.stage.ChiselStage

class Malu() extends Module {
    val io = IO(new Bundle {
        val next_ready = Input(Bool())

        val instruction = Input(new InstructionBundle())
        val valid = Input(Bool())

        val out = Output(new InstructionBundle())
        val out_valid = Output(Bool())

        val flush = Input(Bool())

        val ready = Output(Bool())

        val lsu_broadcast_valid = Input(Bool())
        val alu_broadcast_valid = Input(Bool())
        val jump_broadcast_valid = Input(Bool())

        val lsu_out_valid = Input(Bool())
        val alu_out_valid = Input(Bool())
        val jump_out_valid = Input(Bool())

        val broadcast_free_valid = Output(Bool())
        val broadcast_free_register = Output(UInt(5.W))
        val broadcast_free_value = Output(UInt(32.W))
    })

    val out = RegInit(0.U.asTypeOf(new InstructionBundle))
    io.out := out


    val retire_pending = RegInit(false.B)

    val malu = Module(new MALU_Sub())

    val inflight = RegInit(0.U.asTypeOf(new InstructionBundle))
    val busy     = RegInit(false.B)

    val result_inst  = RegInit(0.U.asTypeOf(new InstructionBundle))
    val result_value = RegInit(0.U(32.W))
    val result_valid = RegInit(false.B)

    val ready = io.next_ready && malu.io.ready && !busy && !result_valid && !retire_pending

    io.ready := ready

    io.broadcast_free_valid := false.B
    io.broadcast_free_register := 0.U
    io.broadcast_free_value := 0.U


    val isM   = io.instruction.opcode === "b0110011".U && io.instruction.func7  === "b0000001".U
    val isDiv = io.instruction.func3(2)          
    val issue = ready && io.valid && isM

 
    val active = Mux(busy, inflight, io.instruction)

    malu.io.func7 := active.func7
    malu.io.func3 := active.func3
    malu.io.a := active.rs1_value
    malu.io.b := active.rs2_value
    malu.io.start := issue


    when(issue && !isDiv) {
        result_inst  := io.instruction
        result_value := malu.io.output
        result_valid := true.B
    }

    when(issue && isDiv) {
        inflight := io.instruction
        busy := true.B
    }

    when(busy && malu.io.valid) {
        result_inst  := inflight
        result_value := malu.io.output
        result_valid := true.B
        busy  := false.B
    }


    val bus_free = !io.lsu_broadcast_valid &&
                   !io.alu_broadcast_valid &&
                   !io.jump_broadcast_valid

    val retire_free = !io.lsu_out_valid &&
                      !io.alu_out_valid &&
                      !io.jump_out_valid

    io.out_valid := retire_pending && retire_free

    when(retire_pending && retire_free) {
        retire_pending := false.B
    }

    when(result_valid && bus_free) {
        out := result_inst
        out.rd_value := result_value
        retire_pending := true.B

        io.broadcast_free_valid := result_inst.rd =/= 0.U
        io.broadcast_free_register := result_inst.rd
        io.broadcast_free_value := result_value

        result_valid := false.B
    }

    when(io.flush) {
        out := 0.U.asTypeOf(new InstructionBundle)
        inflight := 0.U.asTypeOf(new InstructionBundle)
        busy := false.B
        result_inst  := 0.U.asTypeOf(new InstructionBundle)
        result_value := 0.U
        result_valid := false.B
        retire_pending := false.B

        io.broadcast_free_valid := false.B
        io.out_valid := false.B
    }
}
