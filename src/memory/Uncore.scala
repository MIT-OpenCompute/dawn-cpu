package RISCV
import chisel3._
import chisel3.util._
import _root_.circt.stage.ChiselStage
import chisel3.util.experimental.loadMemoryFromFileInline


  // Uncore is what intel used to call their old out of core stuff, and i think its a cool name
class Uncore(lineWidth: Int, nCores: Int = 1) extends Module {
  val io = IO(new Bundle {
    val bus_req   = Flipped(Vec(2*nCores, Decoupled(new MemLineReq(lineWidth))))
    val bus_resp  = Output(UInt(lineWidth.W))
    val bus_grant = Output(Vec(2*nCores, Bool()))
    val mem_req   = Decoupled(new MemLineReq(lineWidth))
    val mem_resp  = Input(UInt(lineWidth.W))
    val mem_valid = Input(Bool())
  })


  val arbiter = Module(new CacheArbiter(lineWidth, 2*nCores))
  val l2_cache = Module(new L2Cache(lineWidth))

  io.mem_req <> l2_cache.io.mem_req
  l2_cache.io.mem_resp := io.mem_resp
  l2_cache.io.mem_valid := io.mem_valid

  l2_cache.io.req  <> arbiter.io.mem_req
  arbiter.io.mem_resp:= l2_cache.io.mem_resp_in
  arbiter.io.mem_valid := l2_cache.io.mem_valid_in

  for (i <- 0 until 2*nCores) {
    arbiter.io.cache_req(i) <> io.bus_req(i)
    io.bus_grant(i) := arbiter.io.resp_to_cache(i)
  }
  io.bus_resp := l2_cache.io.mem_resp_in
}



