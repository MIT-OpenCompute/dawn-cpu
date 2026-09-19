package RISCV
import chisel3._
import chisel3.util._
import _root_.circt.stage.ChiselStage
import chisel3.util.experimental.loadMemoryFromFileInline



class MemoryWrapper(lineWidth: Int = 128, clockFreq: Int = 167000000, baud: Int = 6000000) extends Module {
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



    val bus_req   = Vec(2, Decoupled(new MemLineReq(lineWidth)))
    val bus_resp  = Input(UInt(lineWidth.W))
    val bus_grant = Input(Vec(2, Bool()))

    val address_vga = Output(UInt(32.W))
    val write_vga = Output(Bool())
    val write_value_vga = Output(UInt(32.W))
    val switch = Output(Bool())
    val handshake_bypass = Output(Bool())

    val rxd = Input(Bool())
    val txd = Output(Bool())

    val latch_out = Output(UInt(8.W))
  })


  val mem = Module(new MemoryInterface(lineWidth))
  val hardwareTimer = Module(new HardwareTimer(clockFreq))
  val keyTracker = Module(new UartKeyboardTracker(clockFreq, baud))
  val uartTx = Module(new UartTxFifo(clockFreq, baud)) 
  val last_bypass = RegInit(false.B)
  val bypass_val = RegInit(0.U(32.W))
  
  io.txd := uartTx.io.out
  uartTx.io.in.bits  := 0.U
  uartTx.io.in.valid := false.B

  keyTracker.io.rxd := io.rxd
  mem.io.icache_req := io.icache_req
  mem.io.icache_start := io.icache_start
  io.icache_ready := mem.io.icache_ready
  io.icache_valid := mem.io.icache_valid
  io.icache_data := mem.io.icache_data

  val KEYTRACKER_BASE = 0x08000008.U
  val is_vga     = io.dcache_req.address >= 0x10000000.U
  val is_htimer  = io.dcache_req.address === 0x8000004.U
  val is_keytracker = io.dcache_req.address >= KEYTRACKER_BASE && io.dcache_req.address < (KEYTRACKER_BASE + 0x2C.U)
  val is_uarttx = io.dcache_req.address === 0x08000034.U
  val is_debug_char = io.dcache_req.address === 0x70000000.U
  val is_debug_num = io.dcache_req.address === 0x70000008.U
  val is_switch = io.dcache_req.address === 0x9000000.U
  val is_excep = is_vga || is_htimer || is_keytracker || is_uarttx || is_debug_char || is_debug_num || is_switch
  last_bypass := is_excep
  when(is_vga &&  io.dcache_start){
    // printf("is vga bypassing\n\n\n\n")
  }

  val latch_out = RegInit(0.U(16.W))
  io.latch_out := latch_out
  when(is_debug_char && io.dcache_start) {
      printf("%c", (io.dcache_req.write_data)(7,0));
  }

  when(is_debug_num && io.dcache_start) {
      printf("0x%x", io.dcache_req.write_data);
      when(io.dcache_req.write_data === 100.U){
        latch_out := 320.U
      }
  }.elsewhen(latch_out >0.U){
    latch_out:=latch_out -1.U
  }


  mem.io.dcache_req := io.dcache_req
  mem.io.dcache_start := io.dcache_start && !is_excep
  io.dcache_ready := mem.io.dcache_ready
  // io.dcache_valid := mem.io.dcache_valid || last_bypass
  io.dcache_data := mem.io.dcache_data
  mem.io.dcache_rd := io.dcache_rd
  mem.io.dcache_wen := io.dcache_wen
  io.dcache_rd_out := mem.io.dcache_rd_out
  io.dcache_wen_out := mem.io.dcache_wen_out

  val dcache_bypass = io.dcache_start && is_excep
  val bypass_valid  = RegNext(dcache_bypass, false.B)
  val bypass_rd     = RegNext(io.dcache_rd)
  val bypass_wen    = RegNext(io.dcache_wen)

  io.dcache_valid := mem.io.dcache_valid || bypass_valid
  when(bypass_valid) {
    io.dcache_data := bypass_val
    io.dcache_rd_out := bypass_rd
    io.dcache_wen_out := bypass_wen
  }

  val keytracker_word = (io.dcache_req.address - KEYTRACKER_BASE)(5, 2)

  val keytracker_rdata = MuxLookup(keytracker_word, 0.U(32.W))(Seq(
    0.U  -> keyTracker.io.keyDown(31, 0),
    1.U  -> keyTracker.io.keyDown(63, 32),
    2.U  -> keyTracker.io.keyDown(95, 64),
    3.U  -> keyTracker.io.keyDown(127, 96),
    4.U  -> keyTracker.io.keyDown(159, 128),
    5.U  -> keyTracker.io.keyDown(191, 160),
    6.U  -> keyTracker.io.keyDown(223, 192),
    7.U  -> keyTracker.io.keyDown(255, 224),
    8.U  -> keyTracker.io.lastCode,
    9.U  -> Cat(0.U(30.W), keyTracker.io.eventValid, keyTracker.io.lastPressed),
    10.U -> keyTracker.io.numDown
  ))




  io.address_vga := io.dcache_req.address - 0x10000000.U
  io.write_vga := is_vga &&  io.dcache_start
  io.write_value_vga := io.dcache_req.write_data(23,20) ## io.dcache_req.write_data(15,12) ## io.dcache_req.write_data(7,4)
  io.handshake_bypass := is_excep

 

  when(is_htimer) {
    bypass_val := hardwareTimer.io.micros
  }.elsewhen(is_keytracker) {
    bypass_val := keytracker_rdata
  }


  when(is_uarttx && uartTx.io.in.ready){
    uartTx.io.in.bits := io.dcache_req.write_data(7,0)
    uartTx.io.in.valid := true.B
    
  }

  when(is_switch){
    io.switch:= 1.U
  }.otherwise{
    io.switch := 0.U
  }




  io.bus_req <> mem.io.bus_req
  mem.io.bus_resp  := io.bus_resp
  mem.io.bus_grant := io.bus_grant


   //i needs a handshake bypass


}

