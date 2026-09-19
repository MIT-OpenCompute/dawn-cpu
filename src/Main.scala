package RISCV

import chisel3._
import _root_.circt.stage.ChiselStage
import scala.math._
import chisel3.util._


class Main(lineWidth: Int = 512)  extends Module {
    val io = IO(new Bundle {
        val execute = Input(Bool())


        val vga_clk = Input(Clock());
        val hsync = Output(Bool())
        val vsync = Output(Bool())
        val rgb = Output(UInt(12.W))
        val blanking = Output(Bool())

        val mem_req   = Decoupled(new MemLineReq(lineWidth))   
        val mem_resp  = Input(UInt(lineWidth.W))
        val mem_valid = Input(Bool()) 

        val rxd = Input(Bool())
        val txd = Output(Bool())


        val program_pointer = Output(UInt(32.W))


    })
    val memory = Module(new MemoryWrapper(lineWidth))

    val vga_controller = Module(new VGAController())
    val core = Module(new Core())
    val uncore = Module(new Uncore(lineWidth))

    for (j <- 0 until 2) {
      uncore.io.bus_req(j) <> memory.io.bus_req(j)
      memory.io.bus_grant(j) := uncore.io.bus_grant(j)
    }
    memory.io.bus_resp := uncore.io.bus_resp

    io.mem_req <> uncore.io.mem_req
    uncore.io.mem_resp := io.mem_resp
    uncore.io.mem_valid := io.mem_valid

   

    memory.io.icache_req.address := core.io.program_memory_address
    memory.io.icache_req.write_data := 0.U
    memory.io.icache_req.op := MemOp.LW
    memory.io.icache_req.read := true.B
    memory.io.icache_req.write := false.B
    memory.io.icache_start := core.io.program_memory_requested
    core.io.program_memory_ready := memory.io.icache_ready
    core.io.program_memory_valid := memory.io.icache_valid
    core.io.program_memory_value := memory.io.icache_data




    core.io.dcache_ready := memory.io.dcache_ready
    core.io.dcache_valid := memory.io.dcache_valid
    core.io.dcache_data := memory.io.dcache_data
    core.io.mem_rd := memory.io.dcache_rd_out
    core.io.mem_wen := memory.io.dcache_wen_out
    memory.io.dcache_req := core.io.dcache_req
    memory.io.dcache_start := core.io.dcache_start
    memory.io.dcache_rd := core.io.dcache_rd
    memory.io.dcache_wen := core.io.dcache_wen


    memory.io.rxd := io.rxd
    io.txd := memory.io.txd


    vga_controller.io.address := memory.io.address_vga
    vga_controller.io.write := memory.io.write_vga
    vga_controller.io.write_value := memory.io.write_value_vga
    vga_controller.io.read_clk := io.vga_clk
    io.hsync := vga_controller.io.hsync
    io.vsync := vga_controller.io.vsync
    io.rgb := vga_controller.io.rgb
    io.blanking := vga_controller.io.blanking

    core.io.execute := io.execute
    core.io.debug := 0.U



    io.program_pointer := core.io.program_pointer

    // printf("[Main] dmemory read requested: %b\n", core.io.data_memory_read_requested)
    // printf("[Main] dmemory read valid: %b\n", memory_read_requested_2)
}

object Main extends App {
    ChiselStage.emitSystemVerilogFile(
      new Main(),
      firtoolOpts = Array(
        "-disable-all-randomization",
        "-strip-debug-info",
        "-default-layer-specialization=enable"
      ),
      args = Array("--target-dir", "generated")
    )
}
