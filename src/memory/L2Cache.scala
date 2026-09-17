package RISCV
import chisel3._
import chisel3.util._
import _root_.circt.stage.ChiselStage

class L2Cache(lineWidth: Int = 128) extends Module {
  val io = IO(new Bundle {
    val req = Flipped(Decoupled(new MemLineReq(lineWidth)))
    val mem_resp_in = Output(UInt(lineWidth.W))
    val mem_valid_in = Output(Bool())
    val mem_req = Decoupled(new MemLineReq(lineWidth))
    val mem_resp = Input(UInt(lineWidth.W))
    val mem_valid = Input(Bool())

  })
    val CACHE_SETS = 1024
    val LINE_WIDTH_WORDS = lineWidth/32
    val LOG_CACHE_SETS = log2Up(CACHE_SETS)
    val LOG_LINE_WIDTH_WORDS = log2Up(LINE_WIDTH_WORDS)

    val cache_tag = Wire(UInt((32-LOG_CACHE_SETS-LOG_LINE_WIDTH_WORDS-2).W))

    def getIndex(addr: UInt): UInt = addr(LOG_LINE_WIDTH_WORDS + LOG_CACHE_SETS + 1, LOG_LINE_WIDTH_WORDS + 2)
    def getTag(addr: UInt): UInt = addr(31, LOG_LINE_WIDTH_WORDS + LOG_CACHE_SETS + 2)
    def getLineAddr(addr: UInt): UInt = addr(31, LOG_LINE_WIDTH_WORDS + 2)

    val meta_array = SyncReadMem(CACHE_SETS, UInt(((32-LOG_CACHE_SETS-LOG_LINE_WIDTH_WORDS)).W)) // status(2bit) + tag
    val data_array = SyncReadMem(CACHE_SETS, UInt(lineWidth.W))

    val state = RegInit(CacheState.IDLE)
    val current_mem_req = RegInit(0.U.asTypeOf(new MemLineReq(lineWidth)))
    val lookup_address = Mux(io.req.valid, io.req.bits.addr, current_mem_req.addr)
    val lookup_address_reg = RegNext(lookup_address)

    cache_tag := getTag(lookup_address_reg)
    val raw_index = getIndex(lookup_address)
    val read_enable = state === CacheState.IDLE || state === CacheState.LOOKUP || state === CacheState.MISS
    val data_out = data_array.read(raw_index, read_enable)
    val meta_out = meta_array.read(raw_index, read_enable)

    val data_wr_en = WireDefault(false.B)
    val data_wr_data = WireDefault(0.U(lineWidth.W))
    val meta_wr_en = WireDefault(false.B)
    val meta_wr_data = WireDefault(0.U(meta_array.t.getWidth.W))
    val write_addr = getIndex(current_mem_req.addr)
    
    io.mem_valid_in := false.B
    io.mem_resp_in  := 0.U

    val status = meta_out(meta_out.getWidth-1, meta_out.getWidth-2)
    val tag = meta_out(meta_out.getWidth-3, 0)
    val wb_data_reg = RegInit(0.U(lineWidth.W))
    val wb_addr_reg = RegInit(0.U(32.W))
    val hit = cache_tag === tag && status(1) === 1.U
    io.req.ready := state === CacheState.IDLE || (state === CacheState.LOOKUP && hit && !current_mem_req.write)

    val want_fill = WireDefault(false.B)                        
    val want_wb = state === CacheState.WRITEBACK            
    val fill_addr = Cat(getLineAddr(current_mem_req.addr), 0.U((LOG_LINE_WIDTH_WORDS + 2).W))

    val mem_req_accepted = RegInit(false.B)

    io.mem_req.valid := (want_fill || want_wb) && !mem_req_accepted
    io.mem_req.bits.write := want_wb
    io.mem_req.bits.addr  := Mux(want_wb, wb_addr_reg, fill_addr)
    io.mem_req.bits.wdata := wb_data_reg
    val mem_ack = io.mem_valid && mem_req_accepted
    
    when(io.mem_req.fire) { mem_req_accepted := true.B }
    when(mem_ack) { mem_req_accepted := false.B }

    switch(state) {
        is(CacheState.IDLE) {
            when(io.req.valid) {
                current_mem_req := io.req.bits
                state := CacheState.LOOKUP
            }
        }

        is(CacheState.LOOKUP) {
            when(hit) { // HIT
                    when(io.req.valid){
                        current_mem_req := io.req.bits
                        state := CacheState.LOOKUP
                    }.otherwise{
                        state := CacheState.IDLE
                    }

                when(current_mem_req.write) {
                    data_wr_en   := true.B
                    data_wr_data := current_mem_req.wdata
                    meta_wr_en   := true.B
                    meta_wr_data := Cat("b11".U(2.W), getTag(current_mem_req.addr))

                    io.mem_valid_in := true.B
                }.otherwise {
                    io.mem_valid_in := true.B
                    io.mem_resp_in  := data_out
                }
            }.otherwise { // MISS
                when(status === "b11".U) {  //DIrty Miss
                    wb_data_reg := data_out
                    wb_addr_reg := Cat(tag, getIndex(current_mem_req.addr), 0.U((LOG_LINE_WIDTH_WORDS + 2).W))
                    state       := CacheState.WRITEBACK
                }.otherwise {            // Clean miss
                    state := CacheState.MISS
                }
            }
        }

        is(CacheState.WRITEBACK) {
            when(mem_ack) {
                state := CacheState.MISS
            }
        }

        is(CacheState.MISS) {
            when(!mem_ack && !current_mem_req.write) {
                want_fill := true.B
            }.otherwise {
                when(current_mem_req.write) {
                    data_wr_en   := true.B
                    data_wr_data := current_mem_req.wdata
                    meta_wr_en   := true.B
                    meta_wr_data := Cat("b11".U(2.W), getTag(current_mem_req.addr))
                    io.mem_valid_in := true.B
                }.otherwise {
                    data_wr_en   := true.B
                    data_wr_data := io.mem_resp
                    meta_wr_en   := true.B
                    meta_wr_data := Cat("b10".U(2.W), getTag(current_mem_req.addr))
                    io.mem_resp_in  := io.mem_resp
                    io.mem_valid_in := true.B
                }
                state := CacheState.IDLE
            }
        }
    }

    when(data_wr_en) { data_array.write(write_addr, data_wr_data) }
    when(meta_wr_en) { meta_array.write(write_addr, meta_wr_data) }

}
