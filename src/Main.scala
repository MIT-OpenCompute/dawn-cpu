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

    val vga_controller = Module(new VGAController())
    val uncore = Module(new Uncore(lineWidth))
    val ccx = Module(new CCX(lineWidth))

    for (j <- 0 until 2) {
      uncore.io.bus_req(j) <> ccx.io.bus_req(j)
      ccx.io.bus_grant(j) := uncore.io.bus_grant(j)
    }
    ccx.io.bus_resp := uncore.io.bus_resp

    io.mem_req <> uncore.io.mem_req
    uncore.io.mem_resp := io.mem_resp
    uncore.io.mem_valid := io.mem_valid

   

 


    ccx.io.rxd := io.rxd
    io.txd := ccx.io.txd


    vga_controller.io.address := ccx.io.address_vga
    vga_controller.io.write := ccx.io.write_vga
    vga_controller.io.write_value := ccx.io.write_value_vga
    vga_controller.io.read_clk := io.vga_clk
    io.hsync := vga_controller.io.hsync
    io.vsync := vga_controller.io.vsync
    io.rgb := vga_controller.io.rgb
    io.blanking := vga_controller.io.blanking

    ccx.io.execute := io.execute



    io.program_pointer := ccx.io.program_pointer

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
