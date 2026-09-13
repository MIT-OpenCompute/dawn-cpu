package RISCV

import chisel3._
import _root_.circt.stage.ChiselStage
import scala.math._

class Core() extends Module {
    val io = IO(new Bundle {
        val execute = Input(Bool())

        val program_pointer = Output(UInt(32.W))

        val program_memory_requested = Output(Bool())
        val program_memory_address = Output(UInt(32.W))
        val program_memory_value = Input(UInt(32.W))
        val program_memory_ready = Input(Bool())
        val program_memory_valid = Input(Bool())

        val dcache_req = Output(new MemReq)
        val dcache_start = Output(Bool())
        val dcache_ready = Input(Bool())
        val dcache_valid = Input(Bool())
        val dcache_data = Input(UInt(32.W))
        val dcache_rd = Output(UInt(5.W))
        val dcache_wen = Output(Bool())

        val mem_rd = Input(UInt(5.W))
        val mem_wen = Input(Bool())
        val debug = Input(Bool())
    })

    val program_pointer = RegInit(0.U(32.W))
    val next_program_pointer = WireDefault(program_pointer)
    val registers = Module(new Registers())
    val fetch_stage = Module(new FetchStage())
    val decode_stage = Module(new DecodeStage())
    val read_stage = Module(new ReadStage())
    val instruction_dispatch_queue = Module(new InstructionDispatchQueue())
    val alu_pe = Module(new Alu)
    val malu_pe = Module(new Malu)
    val lsu_pe = Module(new Lsu)
    val jump_unit = Module(new JumpUnit)
    val reorder_buffer = Module(new ReorderBuffer())
    val branch_predictor = Module(new BranchPredictor())

    io.program_memory_address := program_pointer
    when(jump_unit.io.flush && jump_unit.io.target_program_pointer === 0.U) {
    printf("FLUSH from ip %d to %d (op %b) inst %x\nimm:%d func3:%d func7 %d rd %d  rs2 %d rs1 %d \n",
      jump_unit.io.instruction.instruction_pointer,
      jump_unit.io.target_program_pointer,
      jump_unit.io.instruction.opcode,
      jump_unit.io.instruction.inst,
      jump_unit.io.instruction.immediate,
      jump_unit.io.instruction.func3,
      jump_unit.io.instruction.func7,
      jump_unit.io.instruction.rd,
      jump_unit.io.instruction.rs2,
      jump_unit.io.instruction.rs1
      )
}
    when(io.execute){
// printf("PP: %d Data: %x Stall %b MV %b MR %b | dec_rdy %b  rob_full %b idq_rdy %b\n",
//   program_pointer, io.program_memory_value, fetch_stage.io.ready,
//   io.program_memory_valid, io.program_memory_requested,
//   decode_stage.io.ready,
//   reorder_buffer.io.full, instruction_dispatch_queue.io.ready)    
//   when(io.execute) {
//   printf("  LSU: start %b dc_valid %b dc_rdy %b | lsu_rdy %b lsu_out_v %b |  idq_lsu_v %b\n",
//     lsu_pe.io.dcache_start,io.dcache_valid, io.dcache_ready,
//     lsu_pe.io.ready, lsu_pe.io.out_valid,
//      instruction_dispatch_queue.io.lsu_out_valid)
// }  // printf("R1: %x , R2 %x, R3 %x, R4 %x\n", registers.io.debug_1,registers.io.debug_2,registers.io.debug_3,registers.io.debug_4)
    }
    io.program_memory_requested := fetch_stage.io.memory_read_requested

    registers.io.write_enable := reorder_buffer.io.write_mode === WriteMode.Register
    registers.io.write_address := reorder_buffer.io.write_address
    registers.io.in := reorder_buffer.io.write_value

    registers.io.read_address_a := read_stage.io.read_register_1
    registers.io.read_address_b := read_stage.io.read_register_2

    fetch_stage.io.next_ready := decode_stage.io.ready
    fetch_stage.io.execute := io.execute
    fetch_stage.io.program_pointer := program_pointer
    fetch_stage.io.predicted_program_pointer := branch_predictor.io.predicted_program_pointer
    fetch_stage.io.flush := jump_unit.io.flush
    fetch_stage.io.memory_read_ready := io.program_memory_ready
    fetch_stage.io.memory_read_value := io.program_memory_value
    fetch_stage.io.memory_read_valid := io.program_memory_valid

    branch_predictor.io.program_pointer := next_program_pointer 
    branch_predictor.io.jump_valid := jump_unit.io.flush
    branch_predictor.io.jump_instruction_pointer := jump_unit.io.source_program_pointer
    branch_predictor.io.jump_target := jump_unit.io.target_program_pointer

    when(fetch_stage.io.ready) {
        next_program_pointer := branch_predictor.io.predicted_program_pointer
    }
    when(jump_unit.io.flush){
      next_program_pointer := jump_unit.io.target_program_pointer
    }
    program_pointer := next_program_pointer

    decode_stage.io.next_ready := read_stage.io.ready
    decode_stage.io.instruction := fetch_stage.io.next_instruction
    decode_stage.io.instruction_pointer := fetch_stage.io.next_instruction_pointer
    decode_stage.io.predicted_instruction_pointer := fetch_stage.io.next_predicted_instruction_pointer
    decode_stage.io.reorder_buffer_head := reorder_buffer.io.head
    decode_stage.io.valid := fetch_stage.io.next_valid
    decode_stage.io.flush := jump_unit.io.flush

    read_stage.io.instruction := decode_stage.io.next_instruction
    read_stage.io.valid := decode_stage.io.next_valid
    read_stage.io.broadcast_free_valid := instruction_dispatch_queue.io.broadcast_free_valid
    read_stage.io.broadcast_free_value := instruction_dispatch_queue.io.broadcast_free_value
    read_stage.io.broadcast_free_register := instruction_dispatch_queue.io.broadcast_free_register
    read_stage.io.flush := jump_unit.io.flush

    read_stage.io.read_result_1 := registers.io.out_a
    read_stage.io.read_result_2 := registers.io.out_b
    read_stage.io.next_ready := instruction_dispatch_queue.io.ready

    instruction_dispatch_queue.io.instruction := read_stage.io.next_instruction
    instruction_dispatch_queue.io.valid := read_stage.io.next_valid
    instruction_dispatch_queue.io.broadcast_free_valid := lsu_pe.io.broadcast_free_valid || alu_pe.io.broadcast_free_valid || malu_pe.io.broadcast_free_valid || jump_unit.io.broadcast_free_valid
    instruction_dispatch_queue.io.broadcast_free_value :=  Mux(
        jump_unit.io.broadcast_free_valid,
        jump_unit.io.broadcast_free_value,
        Mux(
            lsu_pe.io.broadcast_free_valid,
            lsu_pe.io.broadcast_free_value,
            Mux(
                alu_pe.io.broadcast_free_valid,
                alu_pe.io.broadcast_free_value,
                malu_pe.io.broadcast_free_value
            )
        )
    )
    instruction_dispatch_queue.io.broadcast_free_register := Mux(
        jump_unit.io.broadcast_free_valid,
        jump_unit.io.broadcast_free_register,
        Mux(
            lsu_pe.io.broadcast_free_valid,
            lsu_pe.io.broadcast_free_register,
            Mux(
                alu_pe.io.broadcast_free_valid,
                alu_pe.io.broadcast_free_register,
                malu_pe.io.broadcast_free_register
            )
        )
    )
    instruction_dispatch_queue.io.jump_unit_ready := jump_unit.io.ready
    instruction_dispatch_queue.io.lsu_ready := lsu_pe.io.ready
    instruction_dispatch_queue.io.alu_ready := alu_pe.io.ready
    instruction_dispatch_queue.io.malu_ready := malu_pe.io.ready
    instruction_dispatch_queue.io.reorder_buffer_tail := reorder_buffer.io.tail
    instruction_dispatch_queue.io.flush := jump_unit.io.flush
    instruction_dispatch_queue.io.debug := io.debug

    jump_unit.io.instruction := instruction_dispatch_queue.io.jump_unit_out
    jump_unit.io.valid := instruction_dispatch_queue.io.jump_unit_out_valid
    jump_unit.io.next_ready := !reorder_buffer.io.full

    // when(jump_unit.io.flush) {
    //     program_pointer := jump_unit.io.target_program_pointer

    //     // printf("Flush jumping from %d to %d\n", jump_unit.io.source_program_pointer, jump_unit.io.target_program_pointer)
    // }

    lsu_pe.io.instruction := instruction_dispatch_queue.io.lsu_out
    lsu_pe.io.valid := instruction_dispatch_queue.io.lsu_out_valid
    lsu_pe.io.next_ready := !reorder_buffer.io.full
    lsu_pe.io.flush := jump_unit.io.flush

    io.dcache_req := lsu_pe.io.dcache_req
    io.dcache_start := lsu_pe.io.dcache_start || (reorder_buffer.io.write_mode === WriteMode.Memory)
    io.dcache_rd :=lsu_pe.io.dcache_rd 
    io.dcache_wen := true.B
    lsu_pe.io.dcache_data := io.dcache_data
    lsu_pe.io.dcache_ready := io.dcache_ready && !(reorder_buffer.io.write_mode === WriteMode.Memory)
    lsu_pe.io.dcache_valid := io.dcache_valid && !(reorder_buffer.io.write_mode === WriteMode.Memory) 
    lsu_pe.io.dcache_rd_out := io.mem_rd
    lsu_pe.io.dcache_wen_out := io.mem_wen


    when(reorder_buffer.io.write_mode === WriteMode.Memory) {
      io.dcache_req.op := reorder_buffer.io.dcache_op
      io.dcache_req.address :=  reorder_buffer.io.write_address
      io.dcache_req.write_data := reorder_buffer.io.write_value
      io.dcache_req.read := false.B
      io.dcache_req.write := true.B
      io.dcache_rd := 0.U
      io.dcache_wen := false.B

    }
    reorder_buffer.io.write_ready := io.dcache_ready
    reorder_buffer.io.write_complete := io.dcache_valid
    


    alu_pe.io.instruction := instruction_dispatch_queue.io.alu_out
    alu_pe.io.valid := instruction_dispatch_queue.io.alu_out_valid
    alu_pe.io.next_ready := !reorder_buffer.io.full
    alu_pe.io.lsu_broadcast_valid := lsu_pe.io.broadcast_free_valid
    alu_pe.io.flush := jump_unit.io.flush

    malu_pe.io.instruction := instruction_dispatch_queue.io.malu_out
    malu_pe.io.valid := instruction_dispatch_queue.io.malu_out_valid
    malu_pe.io.next_ready := !reorder_buffer.io.full
    malu_pe.io.lsu_broadcast_valid := lsu_pe.io.broadcast_free_valid
    malu_pe.io.alu_broadcast_valid := alu_pe.io.broadcast_free_valid
    malu_pe.io.jump_broadcast_valid := jump_unit.io.broadcast_free_valid
    malu_pe.io.flush := jump_unit.io.flush
    malu_pe.io.lsu_out_valid := lsu_pe.io.out_valid
    malu_pe.io.alu_out_valid := alu_pe.io.out_valid
    malu_pe.io.jump_out_valid := jump_unit.io.out_valid

    reorder_buffer.io.buffer_entry.value := decode_stage.io.next_instruction.rd_value
    reorder_buffer.io.buffer_entry.rd := decode_stage.io.next_instruction.rd
    reorder_buffer.io.buffer_entry.program_pointer := decode_stage.io.next_instruction.instruction_pointer
    reorder_buffer.io.buffer_entry.mode := decode_stage.io.next_instruction.write_mode
    reorder_buffer.io.buffer_entry.complete := false.B
    reorder_buffer.io.buffer_entry.func3 := decode_stage.io.next_instruction.func3
    reorder_buffer.io.valid := decode_stage.io.next_valid && read_stage.io.ready

    // printf("Read stage ready: %b\n", read_stage.io.ready)

    // printf("JU out: %b\n", jump_unit.io.out_valid)
    // printf("LSU out: %b\n", lsu_pe.io.out_valid)
    // printf("ALU out: %b\n", alu_pe.io.out_valid)

    reorder_buffer.io.complete_instruction := Mux(
        jump_unit.io.out_valid,
        jump_unit.io.out,
        Mux(lsu_pe.io.out_valid, lsu_pe.io.out, Mux(alu_pe.io.out_valid, alu_pe.io.out, malu_pe.io.out))
      )
    reorder_buffer.io.complete_valid := jump_unit.io.out_valid || lsu_pe.io.out_valid || alu_pe.io.out_valid || malu_pe.io.out_valid
    reorder_buffer.io.flush := jump_unit.io.flush

    io.program_pointer := program_pointer

    when(io.execute) {
    //     printf("\n\n");

        // printf("Program Pointer: %d\n", program_pointer);

    //     // printf("[Fetch] Next Ready: %b\n", fetch_stage.io.next_ready);
    //     // printf("[Fetch] Execute: %b\n", fetch_stage.io.execute);
    //     // printf("[Fetch] Program Pointer: %b\n", fetch_stage.io.program_pointer);
    //     // printf("[Fetch] Flush: %b\n", fetch_stage.io.flush);
    //     // printf("[Fetch] Memory Read Requested: %b\n", fetch_stage.io.memory_read_requested);
    //     // printf("[Fetch] Memory Read Ready: %b\n", fetch_stage.io.memory_read_ready);
    //     // printf("[Fetch] Memory Read Value: %b\n", fetch_stage.io.memory_read_value);
    //     // printf("[Fetch] Memory Read Valid: %b\n", fetch_stage.io.memory_read_valid);
    //     // printf("[Fetch] Next Instruction: %b\n", fetch_stage.io.next_instruction);
    //     // printf("[Fetch] Next Instruction Pointer: %b\n", fetch_stage.io.next_instruction_pointer);
    //     // printf("[Fetch] Next Valid: %b\n", fetch_stage.io.next_valid);
    //     // printf("[Fetch] Ready: %b\n\n", fetch_stage.io.ready);

    //     // printf("[Decode] Next Ready: %b\n", decode_stage.io.next_ready);
    //     // printf("[Decode] Instruction: %b\n", decode_stage.io.instruction);
    //     // printf("[Decode] Instruction Pointer: %b\n", decode_stage.io.instruction_pointer);
    //     // printf("[Decode] Valid: %b\n", decode_stage.io.valid);
    //     // printf("[Decode] Flush: %b\n", decode_stage.io.flush);
    //     // printf("[Decode] Next Instruction Opcode: %b\n", decode_stage.io.next_instruction.opcode);
    //     // printf("[Decode] Next Valid: %b\n", decode_stage.io.next_valid);
    //     // printf("[Decode] Ready: %b\n\n", decode_stage.io.ready);

    //     // printf("[Read] Next Ready: %b\n", read_stage.io.next_ready);
    //     // printf("[Read] Instruction Opcode: %b\n", read_stage.io.instruction.opcode);
    //     // printf("[Read] Instruction Rs1: %b\n", read_stage.io.instruction.rs1);
    //     // printf("[Read] Instruction Rs2: %b\n", read_stage.io.instruction.rs2);
    //     // printf("[Read] Valid: %b\n", read_stage.io.valid);
    //     // printf("[Read] Broadcast Free Valid: %b\n", read_stage.io.broadcast_free_valid);
    //     // printf("[Read] Broadcast Free Register: %b\n", read_stage.io.broadcast_free_register);
    //     // printf("[Read] Broadcast Free Value: %b\n", read_stage.io.broadcast_free_value);
    //     // printf("[Read] Read Register 1: %b\n", read_stage.io.read_register_1);
    //     // printf("[Read] Read Result 1: %b\n", read_stage.io.read_result_1);
    //     // printf("[Read] Read Register 2: %b\n", read_stage.io.read_register_2);
    //     // printf("[Read] Read Result 2: %b\n", read_stage.io.read_result_2);
    //     // printf("[Read] Next Instruction Opcode: %b\n", read_stage.io.next_instruction.opcode);
    //     // printf("[Read] Next Instruction Rs1: %b\n", read_stage.io.next_instruction.rs1);
    //     // printf("[Read] Next Instruction Rs1 Valid: %b\n", read_stage.io.next_instruction.rs1_dependence_counter);
    //     // printf("[Read] Next Instruction Rs1 Value: %b\n", read_stage.io.next_instruction.rs1_value);
    //     // printf("[Read] Next Instruction Rs2: %b\n", read_stage.io.next_instruction.rs2);
    //     // printf("[Read] Next Instruction Rs2 Valid: %b\n", read_stage.io.next_instruction.rs2_dependence_counter);
    //     // printf("[Read] Next Instruction Rs2 Value: %b\n", read_stage.io.next_instruction.rs2_value);
    //     // printf("[Read] Next Valid: %b\n", read_stage.io.next_valid);
    //     // printf("[Read] Ready: %b\n\n", read_stage.io.ready);

    //     // printf("[IDQ] Instruction: %b\n", instruction_dispatch_queue.io.instruction.opcode);
    //     // printf("[IDQ] Valid: %b\n", instruction_dispatch_queue.io.valid);
    //     // printf("[IDQ] ALU Out Opcode: %b\n", instruction_dispatch_queue.io.alu_out.opcode);
    //     // printf("[IDQ] ALU Out Valid: %b\n", instruction_dispatch_queue.io.alu_out_valid);
    //     // printf("[IDQ] ALU Out Ready: %b\n", instruction_dispatch_queue.io.alu_ready);
    //     // printf("[IDQ] Broadcast Free Valid: %b\n", instruction_dispatch_queue.io.broadcast_free_valid);
    //     // printf("[IDQ] Broadcast Free Register: %b\n", instruction_dispatch_queue.io.broadcast_free_register);
    //     // printf("[IDQ] Broadcast Free Value: %b\n", instruction_dispatch_queue.io.broadcast_free_value);
    //     // printf("[IDQ] Ready: %b\n\n", instruction_dispatch_queue.io.ready);

    //     // printf("[ALU] Next Ready: %b\n", alu_pe.io.next_ready);
    //     // printf("[ALU] Instruction Opcode: %b\n", alu_pe.io.instruction.opcode);
    //     // printf("[ALU] Instruction Rs1: %b\n", alu_pe.io.instruction.rs1);
    //     // printf("[ALU] Instruction Rs1 Valid: %b\n", alu_pe.io.instruction.rs1_dependence_counter);
    //     // printf("[ALU] Instruction Rs1 Value: %b\n", alu_pe.io.instruction.rs1_value);
    //     // printf("[ALU] Instruction Rs2: %b\n", alu_pe.io.instruction.rs2);
    //     // printf("[ALU] Instruction Rs2 Valid: %b\n", alu_pe.io.instruction.rs2_dependence_counter);
    //     // printf("[ALU] Instruction Rs2 Value: %b\n", alu_pe.io.instruction.rs2_value);
    //     // printf("[ALU] Valid: %b\n", alu_pe.io.valid);
    //     // printf("[ALU] Next Instruction Opcode: %b\n", alu_pe.io.out.opcode);
    //     // printf("[ALU] Next Instruction Immediate: %b\n", alu_pe.io.out.immediate);
    //     // printf("[ALU] Next Instruction Rs1: %b\n", alu_pe.io.out.rs1);
    //     // printf("[ALU] Next Instruction Rs1 Valid: %b\n", alu_pe.io.out.rs1_dependence_counter);
    //     // printf("[ALU] Next Instruction Rs1 Value: %b\n", alu_pe.io.out.rs1_value);
    //     // printf("[ALU] Next Instruction Rs2: %b\n", alu_pe.io.out.rs2);
    //     // printf("[ALU] Next Instruction Rs2 Valid: %b\n", alu_pe.io.out.rs2_dependence_counter);
    //     // printf("[ALU] Next Instruction Rs2 Value: %b\n", alu_pe.io.out.rs2_value);
    //     // printf("[ALU] Next Instruction Rd: %b\n", alu_pe.io.out.rd);
    //     // printf("[ALU] Next Instruction Rd Value: %b\n", alu_pe.io.out.rd_value);
    //     // printf("[ALU] Next Instruction Reorder Pointer: %b\n", alu_pe.io.out.reorder_pointer);
    //     // printf("[ALU] Next Valid: %b\n", alu_pe.io.out_valid);
    //     // printf("[ALU] Ready: %b\n\n", alu_pe.io.ready);

    //     // printf("[RB] Full: %b\n", reorder_buffer.io.full);
    //     // printf("[RB] Buffer Entry Program Pointer: %b\n", reorder_buffer.io.buffer_entry.program_pointer);
    //     // printf("[RB] Valid: %b\n", reorder_buffer.io.valid);
    //     // printf("[RB] Complete Pointer: %b\n", reorder_buffer.io.complete_pointer);
    //     // printf("[RB] Complete Valid: %b\n", reorder_buffer.io.complete_valid);
    //     // printf("[RB] Write Value: %b\n", reorder_buffer.io.write_value);
    //     // printf("[RB] Write Address: %b\n", reorder_buffer.io.write_address);
    //     // printf("[RB] Write Mode: %b\n", reorder_buffer.io.write_mode.asUInt);
    //     // printf("[RB] Write Complete: %b\n\n", reorder_buffer.io.write_complete);

    //     // printf("\n\n\n\n\n\n");

    //     printf("[IMemory] Read Valid: %b\n", io.program_memory_valid);
    //     printf("[IMemory] Read Value: %b\n", io.program_memory_value);

    //     printf("[Decode] Next Valid: %b\n", decode_stage.io.next_valid);
    //     printf("[Decode] Next Opcode: %b\n", decode_stage.io.next_instruction.opcode);
    //     printf("[Decode] Next Pe Type: %b\n", decode_stage.io.next_instruction.pe_type.asUInt);

    //     printf("[Memory] Data Memory Read Requested: %b\n", io.data_memory_read_requested);
    //     printf("[Memory] Data Memory Read Address: %b\n", io.data_memory_read_address);
    //     printf("[Memory] Data Memory Read Value: %b\n", io.data_memory_read_value);
    //     printf("[Memory] Data Memory Read Ready: %b\n", io.data_memory_read_ready);
    //     printf("[Memory] Data Memory Read Valid: %b\n", io.data_memory_read_valid);

    //     printf("[LSU] Broadcast Free Valid: %b\n", lsu_pe.io.broadcast_free_valid);
    //     printf("[ALU] Broadcast Free Valid: %b\n", alu_pe.io.broadcast_free_valid);

    //     printf("[IDQ] ALU Out Valid: %b\n", instruction_dispatch_queue.io.alu_out_valid);
    //     printf("[IDQ] LSU Out Valid: %b\n", instruction_dispatch_queue.io.lsu_out_valid);
    //     printf("[IDQ] JU Out Valid: %b\n", instruction_dispatch_queue.io.jump_unit_out_valid);
    //     printf("[IDQ] Broadcast Free Valid: %b\n", instruction_dispatch_queue.io.broadcast_free_valid);
    //     printf("[IDQ] Broadcast Free Register: %b\n", instruction_dispatch_queue.io.broadcast_free_register);
    //     printf("[IDQ] Broadcast Free Value: %b\n", instruction_dispatch_queue.io.broadcast_free_value);

    //     printf("\n\n");
    }
}

object Core extends App {
    ChiselStage.emitSystemVerilogFile(
      new Core(),
      firtoolOpts = Array(
        "-disable-all-randomization",
        "-strip-debug-info",
        "-default-layer-specialization=enable"
      ),
      args = Array("--target-dir", "generated")
    )
}
