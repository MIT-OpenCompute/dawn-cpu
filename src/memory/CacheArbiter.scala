package RISCV
import chisel3._
import chisel3.util._

class MemLineReq(lineWidth: Int = 128) extends Bundle {
  val addr    = UInt(32.W)
  val write   = Bool()
  val wdata   = UInt(lineWidth.W)
}


class CacheArbiter(lineWidth: Int = 128,nCaches: Int = 2) extends Module {
  require(nCaches >= 1)

  val io = IO(new Bundle {
    val cache_req = Flipped(Vec(nCaches, Decoupled(new MemLineReq(lineWidth))))
    val mem_req = Decoupled(new MemLineReq(lineWidth))
    val mem_resp = Input(UInt(lineWidth.W))
    val mem_valid = Input(Bool())
    val resp_to_cache = Output(Vec(nCaches, Bool()))
    val idle = Output(Bool())
  })

  val busy = RegInit(false.B)
  val owner = RegInit(0.U(log2Ceil(nCaches).max(1).W))
  val mem_req_accepted = RegInit(false.B)
  val latched_req = RegInit(0.U.asTypeOf(new MemLineReq(lineWidth)))

  val idle = !busy

  val valids = VecInit(io.cache_req.map(_.valid))
  val chosen = PriorityEncoder(valids)

  when(idle) {
    when(valids.asUInt.orR) {
      busy := true.B
      owner := chosen
      latched_req := io.cache_req(chosen).bits
      mem_req_accepted := false.B
    }
  }.otherwise {
    when(io.mem_req.fire) { mem_req_accepted := true.B }
  }

  when(io.mem_valid) {
    busy := false.B
    mem_req_accepted := false.B
  }

  io.mem_req.valid := busy && !mem_req_accepted
  io.mem_req.bits := latched_req

  for (i <- 0 until nCaches) {
    io.cache_req(i).ready := idle && (chosen === i.U)
    io.resp_to_cache(i) := io.mem_valid && busy && (owner === i.U)
  }

  io.idle := idle
}
