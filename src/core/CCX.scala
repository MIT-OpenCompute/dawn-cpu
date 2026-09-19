package RISCV

import chisel3._
import _root_.circt.stage.ChiselStage
import scala.math._
import chisel3.util._
  
  // core compelex, b/c why not
class CCX(lineWidth: Int, hartId: Int = 0) extends Module {
  val io = IO(new Bundle {
    val bus_req   = Vec(2, Decoupled(new MemLineReq(lineWidth)))
    val bus_resp  = Input(UInt(lineWidth.W))
    val bus_grant = Input(Vec(2, Bool()))

    val execute         = Input(Bool())
    val program_pointer = Output(UInt(32.W))

    val rxd = Input(Bool());  
    val txd = Output(Bool())
    val address_vga = Output(UInt(32.W))
    val write_vga = Output(Bool())
    val write_value_vga = Output(UInt(32.W))
  })

  val core   = Module(new Core())
  val memory = Module(new MemoryWrapper(lineWidth))



  core.io.debug := 0.U
  core.io.execute:= io.execute
  io.txd := memory.io.rxd;
  memory.io.rxd := io.rxd;
  io.address_vga := memory.io.address_vga
  io.write_vga := memory.io.write_vga
  io.write_value_vga := memory.io.write_value_vga
  io.program_pointer := core.io.program_pointer

  io.bus_req <> memory.io.bus_req
  memory.io.bus_resp := io.bus_resp
  memory.io.bus_grant := io.bus_grant


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
}