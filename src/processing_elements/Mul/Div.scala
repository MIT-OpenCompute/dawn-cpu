package RISCV

import chisel3._
import chisel3.util._
import _root_.circt.stage.ChiselStage

object DivState extends ChiselEnum {
  val IDLE, BUSY, DONE = Value
}

class Div extends Module {
    val io = IO(new Bundle {
        val func3 = Input(UInt(3.W));
        val a = Input(UInt(32.W)); // First operand
        val b = Input(UInt(32.W)); // Second operand
        val output = Output(UInt(32.W)); // Result of the operation
        val ready = Output(Bool())
        val valid = Output(Bool())
        val start = Input(Bool())
     
    })
  

    val state = RegInit(DivState.IDLE);

    io.ready := state === DivState.IDLE
    io.valid := false.B


    val func3 = RegInit(0.U(3.W))
    val a = RegInit(0.U(32.W))
    val b = RegInit(0.U(32.W))
    val divNeg = RegInit(false.B)
    val outNeg = RegInit(false.B)
    val counter = RegInit(0.U(6.W))
    val r = RegInit(0.S(33.W))
    val divByZeroCase = RegInit(false.B)
    val overflowCase  = RegInit(false.B)
    val orga  = RegInit(0.U(32.W))
    io.output := 0.U

    
    // val out := RegInit(0.U(32.W))

    switch(state){
      is(DivState.IDLE) {
        when(io.start) {
          // printf("DIV STARTED %b \n\n",io.func3)
          state := DivState.BUSY
          counter := 32.U
          func3 := io.func3
          r := 0.S 
          orga :=io.a
          divByZeroCase := (io.b === 0.U)

          when(io.func3 === "b100".U || io.func3 === "b110".U) {
            val aNeg = io.a(31)
            val bNeg = io.b(31)
            a := Mux(aNeg, (~io.a).asUInt + 1.U, io.a)
            b := Mux(bNeg, (~io.b).asUInt + 1.U, io.b)

            divNeg := aNeg
            outNeg := aNeg ^ bNeg

            val isOverflow = (io.a === "h80000000".U) && (io.b === "hFFFFFFFF".U)
            overflowCase := isOverflow
            when(isOverflow || io.b === 0.U) {
              state := DivState.DONE
            }
          }.otherwise {
            a := io.a
            b := io.b
            divNeg := false.B
            outNeg := false.B
            overflowCase := false.B
            when(io.b === 0.U) {
              state := DivState.DONE
            }
          }
        }
      }
      is(DivState.BUSY){
        val shifted = Cat(r(31, 0), a(31)).asSInt 
        val bExt = b.zext
        val nextR = Mux(r.asSInt >= 0.S, shifted - bExt, shifted + bExt)
        r:= nextR
        a := Cat(a(30,0), !nextR(32))
        counter := counter - 1.U
        when(counter === 1.U){
          r:= Mux(nextR(32), nextR + bExt, nextR)
          state:=DivState.DONE
          

        }


      }
      is(DivState.DONE) {
        val isDivOp = (func3 === "b100".U) || (func3 === "b101".U) 
        val isRemOp = (func3 === "b110".U) || (func3 === "b111".U) 

        val negatedQ = Mux(outNeg, (~a).asUInt + 1.U, a)
        val negatedR = Mux(divNeg, (~r(31, 0)).asUInt + 1.U, r(31, 0))

        io.output := MuxCase(0.U, Seq(
          divByZeroCase -> Mux(isDivOp, "hFFFFFFFF".U, orga),   
          overflowCase  -> Mux(isDivOp, "h80000000".U, 0.U),    
          isDivOp -> negatedQ,
          isRemOp -> negatedR
        ))
        // printf("A: %d  B: %d  C: %d  Cx: %x f3: %b\n", orga, b, io.output,io.output, func3)

        io.valid := true.B
        state := DivState.IDLE
      }


    }


    




  }