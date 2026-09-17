package RISCV
import chisel3._
import chisel3.util._
import _root_.circt.stage.ChiselStage

object MemOp extends ChiselEnum {
  val LW,LH,LB,LBU,LHU,SW,SH,SB = Value
}
class MemReq extends Bundle {
  val address = UInt(32.W)
  val write_data = UInt(32.W)
  val op = MemOp()
  val read = Bool()
  val write = Bool()
}

class MemoryInterface(lineWidth: Int = 128) extends Module {
  val io = IO(new Bundle {
    val icache_req = Input(new MemReq)
    val icache_start = Input(Bool())
    val icache_ready = Output(Bool())
    val icache_valid = Output(Bool())
    val icache_data = Output(UInt(32.W))
    val dcache_req = Input(new MemReq)
    val dcache_start = Input(Bool())
    val dcache_ready = Output(Bool())
    val dcache_valid = Output(Bool())
    val dcache_data = Output(UInt(32.W))
    val dcache_rd = Input(UInt(5.W))
    val dcache_wen = Input(Bool())

    val dcache_rd_out = Output(UInt(5.W))
    val dcache_wen_out = Output(Bool())


    val mem_req = Decoupled(new MemLineReq(lineWidth))   
    val mem_resp = Input(UInt(lineWidth.W))
    val mem_valid = Input(Bool())      

    

})
  val icache = Module(new ICache(lineWidth))
  val dcache = Module(new DCache(lineWidth))
  val arbiter = Module(new CacheArbiter(lineWidth))
  val dcache_queue = Module(new DCacheQueue(lineWidth))
  val l2_cache = Module(new L2Cache(lineWidth))

  io.mem_req <> l2_cache.io.mem_req
  l2_cache.io.mem_resp := io.mem_resp
  l2_cache.io.mem_valid := io.mem_valid

  l2_cache.io.req  <> arbiter.io.mem_req
  arbiter.io.mem_resp:= l2_cache.io.mem_resp_in
  arbiter.io.mem_valid := l2_cache.io.mem_valid_in

  icache.io.req := io.icache_req
  icache.io.start := io.icache_start 
  io.icache_ready := icache.io.ready 
  io.icache_valid := icache.io.done
  io.icache_data := icache.io.data

  dcache.io.req := dcache_queue.io.dcache_req
  dcache.io.start := dcache_queue.io.dcache_start
  dcache_queue.io.req.req := io.dcache_req
  dcache_queue.io.req.rd := io.dcache_rd
  dcache_queue.io.req.wen := io.dcache_wen
  dcache_queue.io.start :=io.dcache_start
  dcache_queue.io.dcache_ready := dcache.io.ready
  dcache_queue.io.dcache_valid := dcache.io.done
  dcache_queue.io.dcache_data := dcache.io.data

  io.dcache_ready := dcache_queue.io.ready 
  dcache_queue.io.flush := false.B
  // when(!io.dcache_ready){
  //   printf("NOTNOTNNOTNONTONOTNONTOTNOTNREADYEREADY READYD READY REDAY READY READY\n\n\n\n\n\n\n")
  // }
  io.dcache_valid := dcache_queue.io.valid
  io.dcache_data := dcache_queue.io.data
  io.dcache_rd_out := dcache_queue.io.rd
  io.dcache_wen_out := dcache_queue.io.wen


  arbiter.io.cache_req(1).valid  := icache.io.miss
  arbiter.io.cache_req(1).bits.addr:= icache.io.line_addr
  arbiter.io.cache_req(1).bits.write :=  false.B
  arbiter.io.cache_req(1).bits.wdata:= 0.U


  arbiter.io.cache_req(0).valid  := (dcache.io.miss || dcache.io.wb) 
  arbiter.io.cache_req(0).bits.addr:= Mux(dcache.io.wb, dcache.io.wb_addr, dcache.io.line_addr)
  arbiter.io.cache_req(0).bits.write :=  dcache.io.wb
  arbiter.io.cache_req(0).bits.wdata:= dcache.io.wb_data


  



  // io.mem_req.valid := arbiter.io.mem_req.valid
  // io.mem_req.bits.write := arbiter.io.mem_req.bits.write
  // io.mem_req.bits.addr := arbiter.io.mem_req.bits.addr
  // io.mem_req.bits.wdata := arbiter.io.mem_req.bits.wdata

  // arbiter.io.mem_req.ready :=  io.mem_req.ready
  // arbiter.io.mem_resp := io.mem_resp
  // arbiter.io.mem_valid := io.mem_valid 

  icache.io.line_result := l2_cache.io.mem_resp_in
  icache.io.line_valid := arbiter.io.resp_to_cache(1)
  
  dcache.io.line_result := l2_cache.io.mem_resp_in
  dcache.io.line_valid := arbiter.io.resp_to_cache(0)



  // when(true.B){
    
  // }

}

object MemoryInterface extends App {
    ChiselStage.emitSystemVerilogFile(
      new MemoryInterface(),
      firtoolOpts = Array(
        "-disable-all-randomization",
        "-strip-debug-info",
        "-default-layer-specialization=enable"
      ),
      args = Array("--target-dir", "generated")
    )
}
