package dana

import Chisel._

class TransactionState extends XFilesBundle {
  val valid = Bool()
  val reserved = Bool()
  val cacheValid = Bool()
  val waiting = Bool()
  val needsLayerInfo = Bool()
  val done = Bool()
  val decInUse = Bool()
  val request = Bool()
  val inFirst = Bool()
  val inLast = Bool()
  // output_layer should be unused according to types.vh
  val cacheIndex = UInt(width = log2Up(cacheNumEntries))
  val asid = UInt(width = asidWidth)
  val tid = UInt(width = tidWidth)
  val nnid = UInt(width = nnidWidth) // formerly nn_hash
  val decimalPoint = UInt(width = decimalPointWidth)
  val numLayers = UInt(width = 16) // [TODO] fragile
  val numNodes = UInt(width = 16) // [TODO] fragile
  val currentNode = UInt(width = 16) // [TODO] fragile
  val currentNodeInLayer = UInt(width = 16) // [TODO] fragile
  val currentLayer = UInt(width = 16) // [TODO] fragile
  val nodesInCurrentLayer = UInt(width = 16) // [TODO] fragile
  val nodesInNextLayer = UInt(width = 16) // [TODO] fragile
  val neuronPointer = UInt(width = 11) // [TODO] fragile
  val countFeedback = UInt(width = feedbackWidth)
  val countPeWrites = UInt(width = 16) // [TODO] fragile
  val readIdx = UInt(width = log2Up(transactionTableSramElements))
  // Additional crap which may be redundant
  val indexElement = UInt(width = log2Up(transactionTableSramElements))
}

class ControlReq extends XFilesBundle {
  // Bools
  val cacheValid = Bool()
  val waiting = Bool()
  val needsLayerInfo = Bool()
  val isDone = Bool()
  val request = Bool()
  val inFirst = Bool()
  val inLast = Bool()
  // Global info
  val tableIndex = UInt(width = log2Up(transactionTableNumEntries))
  val cacheIndex = UInt(width = log2Up(cacheNumEntries))
  val nnid = UInt(width = nnidWidth) // formerly nn_hash
  val asid = UInt(width = asidWidth)
  val tid = UInt(width = tidWidth)
  // State info
  val currentNode = UInt(width = 16) // [TODO] fragile
  val currentNodeInLayer = UInt(width = 16) // [TODO] fragile
  val currentLayer = UInt(width = 16) // [TODO] fragile
  val nodesInCurrentLayer = UInt(width = 16) // [TODO] fragile
  val nodesInNextLayer = UInt(width = 16) // [TODO] fragile
  val neuronPointer = UInt(width = 11) // [TODO] fragile
  val decimalPoint = UInt(width = decimalPointWidth)
}

class ControlResp extends XFilesBundle {
  val readyCache = Bool()
  val readyPeTable = Bool()
  val cacheValid = Bool()
  val tableIndex = UInt(width = log2Up(transactionTableNumEntries))
  val field = UInt(width = 4) // [TODO] fragile on Constants.scala
  val data = Vec.fill(3){UInt(width = 16)} // [TODO] fragile
  val decimalPoint = UInt(width = decimalPointWidth)
  val layerValid = Bool()
  val layerValidIndex = UInt(width = log2Up(transactionTableNumEntries))
}

class XFilesArbiterRespPipe extends XFilesBundle {
  val respType = UInt(width = log2Up(2)) // [TODO] Fragile on Dana enum
  val asid = UInt(width = asidWidth)
  val tid = UInt(width = tidWidth)
  val tidIdx = UInt(width = log2Up(transactionTableNumEntries))
  val readIdx = UInt(width = log2Up(transactionTableSramElements))
  val coreIdx = UInt(width = log2Up(numCores))
  val rd = UInt(width = 5) // Dependent on rocc.scala defined width
}

class TTableControlInterface extends XFilesBundle {
  val req = Decoupled(new ControlReq)
  val resp = Decoupled(new ControlResp).flip
}

class TransactionTableInterface extends XFilesBundle {
  val arbiter = new XFilesBundle {
    val rocc = new RoCCInterface
    val coreIdx = UInt(INPUT, width = log2Up(numCores))
    val indexOut = UInt(OUTPUT, width = log2Up(numCores))
  }
  val control = new TTableControlInterface
  val peTable = (new PETransactionTableInterface).flip
}

class TransactionTable extends XFilesModule {
  // Communication with the X-FILES arbiter
  val io = new TransactionTableInterface

  // IO alises
  val cmd = new XFilesBundle {
    val readOrWrite = io.arbiter.rocc.cmd.bits.inst.funct(0)
    val isNew = io.arbiter.rocc.cmd.bits.inst.funct(1)
    val isLast = io.arbiter.rocc.cmd.bits.inst.funct(2)
    val asid = io.arbiter.rocc.cmd.bits.rs1(asidWidth + tidWidth - 1, tidWidth)
    val tid = io.arbiter.rocc.cmd.bits.rs1(tidWidth - 1, 0)
    val coreIdx = io.arbiter.coreIdx
    val countFeedback =
      io.arbiter.rocc.cmd.bits.rs1(feedbackWidth + asidWidth + tidWidth - 1,
        asidWidth + tidWidth)
    val nnid = io.arbiter.rocc.cmd.bits.rs2(nnidWidth - 1, 0)
    val data = io.arbiter.rocc.cmd.bits.rs2
    val rd = io.arbiter.rocc.cmd.bits.inst.rd
  }

  // Vector of all the table entries
  val table = Vec.fill(transactionTableNumEntries){Reg(new TransactionState)}

  // Vector of the table entry memories
  val mem = Vec.fill(transactionTableNumEntries){
    Module(new SRAMElement(
      dataWidth = elementWidth * elementsPerBlock,
      sramDepth = transactionTableSramBlocks,
      numPorts = 1,
      elementWidth = elementWidth)).io}
  // An entry is free if it is not valid and not reserved
  def isFree(x: TransactionState): Bool = { !x.valid && !x.reserved }
  def derefTid(x: TransactionState, asid: UInt, tid: UInt): Bool = {
    (x.asid === asid) && (x.tid === tid) && (x.valid || x.reserved) }

  // Determine if there exits a free entry in the table and the index
  // of the next availble free entry
  val hasFree = Bool()
  val nextFree = UInt()
  val foundTid = Bool()
  val derefTidIndex = UInt()
  hasFree := table.exists(isFree)
  nextFree := table.indexWhere(isFree)
  foundTid := table.exists(derefTid(_, cmd.asid, cmd.tid))
  derefTidIndex := table.indexWhere(derefTid(_, cmd.asid, cmd.tid))
  // io.arbiter.rocc.cmd.ready := hasFree
  io.arbiter.rocc.cmd.ready := Bool(true)
  io.arbiter.rocc.resp.valid := Bool(false)
  io.arbiter.rocc.resp.bits.rd := UInt(0)
  io.arbiter.rocc.resp.bits.data := UInt(0)

  // Response pipeline to arbiter
  val arbiterRespPipe = Reg(Valid(new XFilesArbiterRespPipe))
  arbiterRespPipe.valid := Bool(false)
  arbiterRespPipe.bits.respType := UInt(0)
  arbiterRespPipe.bits.asid := UInt(0)
  arbiterRespPipe.bits.tid := UInt(0)
  arbiterRespPipe.bits.readIdx := UInt(0)
  arbiterRespPipe.bits.tidIdx := UInt(0)
  arbiterRespPipe.bits.coreIdx := UInt(0)
  arbiterRespPipe.bits.rd := UInt(0)
  io.arbiter.rocc.resp.valid := arbiterRespPipe.valid
  val memDataVec = Vec((0 until elementsPerBlock).map(i => (mem(arbiterRespPipe.bits.tidIdx).dout(0) >> (UInt(elementWidth) * UInt(i)))(elementWidth - 1, 0)))
  switch (arbiterRespPipe.bits.respType) {
    is (e_TID) {
      io.arbiter.rocc.resp.bits.data := arbiterRespPipe.bits.respType ##
        arbiterRespPipe.bits.tid ## UInt(0, width = elementWidth)
    }
    is (e_READ) {
      io.arbiter.rocc.resp.bits.data := arbiterRespPipe.bits.respType ##
        arbiterRespPipe.bits.tid ##
        memDataVec(arbiterRespPipe.bits.readIdx(log2Up(elementsPerBlock) -1, 0))
    }
    is (e_NOT_DONE) {
      io.arbiter.rocc.resp.bits.data := arbiterRespPipe.bits.respType ##
        arbiterRespPipe.bits.tid ## UInt(0, width = elementWidth)
    }
  }
  io.arbiter.rocc.resp.bits.rd := arbiterRespPipe.bits.rd
  io.arbiter.indexOut := arbiterRespPipe.bits.coreIdx

  // Default value assignment
  for (i <- 0 until transactionTableNumEntries) {
    for (j <- 0 until mem(i).numPorts) {
      mem(i).we(j) := Bool(false)
      mem(i).din(j) := UInt(0)
      mem(i).addr(j) := UInt(0)
    }
  }

  when (io.arbiter.rocc.cmd.valid) {
    // This is a new packet
    when (cmd.readOrWrite) { // Write == True
      when (cmd.isNew) {
        table(nextFree).reserved := Bool(true)
        table(nextFree).cacheValid := Bool(false)
        table(nextFree).waiting := Bool(false)
        table(nextFree).needsLayerInfo := Bool(true)
        table(nextFree).inFirst := Bool(true)
        table(nextFree).inLast := Bool(false)
        table(nextFree).asid := cmd.asid
        table(nextFree).tid := cmd.tid
        table(nextFree).nnid := cmd.nnid
        table(nextFree).currentNode := UInt(0)
        table(nextFree).currentLayer := UInt(0)
        table(nextFree).request := Bool(false)
        table(nextFree).countFeedback := cmd.countFeedback
        table(nextFree).done := Bool(false)
        table(nextFree).decInUse := Bool(false)
        table(nextFree).indexElement := UInt(0)
        table(nextFree).countPeWrites := UInt(0)
        table(nextFree).readIdx := UInt(0)
        arbiterRespPipe.valid := Bool(true)
        // Initiate a response that will containt the TID
        arbiterRespPipe.bits.respType := e_TID
        arbiterRespPipe.bits.asid := cmd.asid
        arbiterRespPipe.bits.tid := cmd.tid
        arbiterRespPipe.bits.tidIdx := derefTidIndex
        arbiterRespPipe.bits.coreIdx := cmd.coreIdx
        arbiterRespPipe.bits.rd := cmd.rd
        printf("[INFO] X-Files saw new write request for NNID %x\n", cmd.nnid)
      }
        .elsewhen(cmd.isLast) {
        mem(derefTidIndex).we(0) := Bool(true)
        mem(derefTidIndex).din(0) := cmd.data
        mem(derefTidIndex).addr(0) := table(derefTidIndex).indexElement
        table(derefTidIndex).indexElement :=
          table(derefTidIndex).indexElement + UInt(1)
        table(derefTidIndex).valid := Bool(true)
        printf("[INFO] X-Files saw LAST write request on TID %x with data %x\n",
          cmd.tid, cmd.data);
      }
        // This is an input packet
        .otherwise {
        mem(derefTidIndex).we(0) := Bool(true)
        mem(derefTidIndex).din(0) := cmd.data
        mem(derefTidIndex).addr(0) := table(derefTidIndex).indexElement
        table(derefTidIndex).indexElement :=
          table(derefTidIndex).indexElement + UInt(1)
        printf("[INFO] X-Files saw write request on TID %x with data %x\n",
          cmd.tid, cmd.data);
        // table(derefTidIndex).data() :=
      }
    } .otherwise { // Ths is a read packet.
      when (table(derefTidIndex).done) {
        mem(derefTidIndex).addr(0) := table(derefTidIndex).readIdx
        arbiterRespPipe.valid := Bool(true)
        arbiterRespPipe.bits.respType := e_READ
        arbiterRespPipe.bits.asid := cmd.asid
        arbiterRespPipe.bits.tid := cmd.tid
        arbiterRespPipe.bits.tidIdx := derefTidIndex
        arbiterRespPipe.bits.readIdx := table(derefTidIndex).readIdx
        arbiterRespPipe.bits.coreIdx := cmd.coreIdx
        arbiterRespPipe.bits.rd := cmd.rd
        // Check to see if all outputs have been read
        when (table(derefTidIndex).readIdx ===
          table(derefTidIndex).nodesInCurrentLayer - UInt(1)) {
          table(derefTidIndex).valid := Bool(false)
          table(derefTidIndex).reserved := Bool(false)
        }
        table(derefTidIndex).readIdx := table(derefTidIndex).readIdx + UInt(1)
        printf("[INFO] X-Files saw read request on TID %x\n",
          cmd.tid);
      } .otherwise {
        arbiterRespPipe.valid := Bool(true)
        arbiterRespPipe.bits.respType := e_NOT_DONE
        arbiterRespPipe.bits.coreIdx := cmd.coreIdx
        arbiterRespPipe.bits.rd := cmd.rd
        printf("[INFO] X-Files saw read request on TID %x, but transaction not done!\n",
          cmd.tid);
      }
    }
  }

  // Update the table when we get a request from DANA
  when (io.control.resp.valid) {
    // table(io.control.resp.bits.tableIndex).waiting := Bool(true)
    when (io.control.resp.bits.cacheValid) {
      switch(io.control.resp.bits.field) {
        is(e_TTABLE_CACHE_VALID) {
          table(io.control.resp.bits.tableIndex).cacheValid := Bool(true)
          table(io.control.resp.bits.tableIndex).numLayers :=
          io.control.resp.bits.data(0)
          table(io.control.resp.bits.tableIndex).numNodes :=
          io.control.resp.bits.data(1)
          table(io.control.resp.bits.tableIndex).cacheIndex :=
          io.control.resp.bits.data(2)
          table(io.control.resp.bits.tableIndex).decimalPoint :=
          io.control.resp.bits.decimalPoint
          // Once we know the cache is valid, this entry is no longer waiting
          table(io.control.resp.bits.tableIndex).waiting := Bool(false)
        }
        is(e_TTABLE_LAYER) {
          table(io.control.resp.bits.tableIndex).needsLayerInfo := Bool(false)
          table(io.control.resp.bits.tableIndex).currentNodeInLayer := UInt(0)
          table(io.control.resp.bits.tableIndex).nodesInCurrentLayer := io.control.resp.bits.data(0)
          table(io.control.resp.bits.tableIndex).nodesInNextLayer := io.control.resp.bits.data(1)
          table(io.control.resp.bits.tableIndex).neuronPointer := io.control.resp.bits.data(2)
          // Update the inFirst and inLast Bools. The currentLayer
          // should have already been updated when the request went out.
          table(io.control.resp.bits.tableIndex).inFirst :=
            table(io.control.resp.bits.tableIndex).currentLayer === UInt(0)
          // table(io.control.resp.bits.tableIndex).numLayers - UInt(1)
          table(io.control.resp.bits.tableIndex).inLast :=
            table(io.control.resp.bits.tableIndex).currentLayer ===
            table(io.control.resp.bits.tableIndex).numLayers - UInt(1)
          // If this is a transition into a layer which is not the first
          // layer, then the Transaction Table requests need to block
          // until the Register File has all valid data. [TODO] This is
          // a sub-optimal design choice as PEs should be allowed to
          // start before the Register File has _all_ of its valid data,
          // but I'm leaving this the way it is due to the lack of a
          // non-trivial path to add this functionality.
          table(io.control.resp.bits.tableIndex).waiting :=
            table(io.control.resp.bits.tableIndex).currentLayer > UInt(0)
        }
      }
    }
    // If the register file has all valid entries, then this specific
    // entry should stop waiting. Note, that this logic will correctly
    // overwrite that of the e_TTABLE_LAYER.
    when (io.control.resp.bits.layerValid) {
      table(io.control.resp.bits.layerValidIndex).waiting := Bool(false)
    }
  }

  // Deal with requests from the PE Table. [TODO] This is a somewhat
  // verbose implementation with a largely unused portion of this
  // response pipeline.
  val peRespPipe = Vec.fill(2){Reg(Valid(new PETransactionTableInterfaceResp))}
  val peRespIndex = Reg(next = io.peTable.req.bits.tableIndex)
  peRespPipe(0).valid := Bool(false)
  when (io.peTable.req.valid) {
    // This is either a read or a write request
    when (!io.peTable.req.bits.isWrite) { // This is a read req
      // [TODO] This is using the first address, which should be fine,
      // but there are technically two that we can play with if
      // needed. There may be unintended consequences if some read
      // happens to follow a read very closely.
      mem(io.peTable.req.bits.tableIndex).addr(0) := io.peTable.req.bits.addr
      peRespPipe(0).valid := Bool(true)
      peRespPipe(0).bits.peIndex := io.peTable.req.bits.peIndex
    } .otherwise { // This is a write req
      mem(io.peTable.req.bits.tableIndex).we(0) := Bool(true)
      mem(io.peTable.req.bits.tableIndex).addr(0) := io.peTable.req.bits.addr
      mem(io.peTable.req.bits.tableIndex).din(0) := io.peTable.req.bits.data
      table(io.peTable.req.bits.tableIndex).countPeWrites :=
        table(io.peTable.req.bits.tableIndex).countPeWrites + UInt(1)
      when (table(io.peTable.req.bits.tableIndex).countPeWrites ===
        table(io.peTable.req.bits.tableIndex).nodesInCurrentLayer - UInt(1)) {
        table(io.peTable.req.bits.tableIndex).done := Bool(false)
        table(io.peTable.req.bits.tableIndex).decInUse := Bool(true)
        table(io.peTable.req.bits.tableIndex).waiting := Bool(false)
      }
      printf("[INFO] TTable saw PE-write on ASID/TID %x/%x for data[%d] of %d\n",
        table(io.peTable.req.bits.tableIndex).asid,
        table(io.peTable.req.bits.tableIndex).tid,
        io.peTable.req.bits.addr,
        io.peTable.req.bits.data);
    }
  }
  // Package up the memory response for the response to the PE Table
  peRespPipe(1) := peRespPipe(0)
  when (peRespPipe(0).valid) {
    peRespPipe(1).bits.data := mem(peRespIndex).dout(0)
  }
  io.peTable.resp.valid := peRespPipe(1).valid
  io.peTable.resp.bits.peIndex := peRespPipe(1).bits.peIndex
  io.peTable.resp.bits.data := peRespPipe(1).bits.data

  val readyCache = Reg(next = io.control.resp.bits.readyCache)
  val readyPeTable = Reg(next = io.control.resp.bits.readyPeTable)
  // Round Robin Arbitration of Transaction Table entries. One of
  // these is passed out over an interface to DANA's control module.
  val entryArbiter = Module(new RRArbiter( new ControlReq,
    transactionTableNumEntries))
  // All of these need to be wired up manually as the internal
  // connections aren't IO
  for (i <- 0 until transactionTableNumEntries) {
    // A request is valid if it is valid, is not waiting, and if all
    // the nodes haven't already been allocated (but, the cache must
    // already be valid, i.e., we need to have valid data sitting in
    // the currentNode and numNodes to actually do this comparison).
    entryArbiter.io.in(i).valid := table(i).valid && !table(i).waiting &&
      Reg(next = !entryArbiter.io.out.valid) &&
      ((readyCache && (table(i).decInUse || !table(i).cacheValid ||
        table(i).needsLayerInfo)) ||
       (readyPeTable && (table(i).currentNode != table(i).numNodes)))
    // The other data connections are just aliases to the contents of
    // the specific table entry
    entryArbiter.io.in(i).bits.cacheValid := table(i).cacheValid
    entryArbiter.io.in(i).bits.waiting := table(i).waiting
    entryArbiter.io.in(i).bits.needsLayerInfo := table(i).needsLayerInfo
    entryArbiter.io.in(i).bits.request := table(i).request
    entryArbiter.io.in(i).bits.inFirst := table(i).inFirst
    entryArbiter.io.in(i).bits.inLast := table(i).inLast
    entryArbiter.io.in(i).bits.isDone := table(i).decInUse
    // Global info
    entryArbiter.io.in(i).bits.tableIndex := UInt(i)
    entryArbiter.io.in(i).bits.cacheIndex := table(i).cacheIndex
    entryArbiter.io.in(i).bits.nnid := table(i).nnid
    entryArbiter.io.in(i).bits.asid := table(i).asid
    entryArbiter.io.in(i).bits.tid := table(i).tid
    // State info
    entryArbiter.io.in(i).bits.currentNode := table(i).currentNode
    entryArbiter.io.in(i).bits.currentNodeInLayer := table(i).currentNodeInLayer
    entryArbiter.io.in(i).bits.currentLayer := table(i).currentLayer
    entryArbiter.io.in(i).bits.nodesInCurrentLayer :=table(i).nodesInCurrentLayer
    entryArbiter.io.in(i).bits.nodesInNextLayer := table(i).nodesInNextLayer
    entryArbiter.io.in(i).bits.neuronPointer := table(i).neuronPointer
    entryArbiter.io.in(i).bits.decimalPoint := table(i).decimalPoint
  }
  io.control.req <> entryArbiter.io.out

  // Do a special table update if the arbiter is allowing a request to
  // go through. This decreases the necessary bandwidth between the
  // Control module and the Transaction Table as the Control module
  // doesn't have to generate responses. [TODO] This is somewhat kludgy
  // and may result in excess combinational logic depth. It may be
  // necessary to pipeline this.
  val isCacheReq = entryArbiter.io.out.valid &&
    (!entryArbiter.io.out.bits.cacheValid ||
      entryArbiter.io.out.bits.needsLayerInfo ||
      entryArbiter.io.out.bits.isDone)
  val isPeReq = entryArbiter.io.out.valid &&
    (entryArbiter.io.out.bits.cacheValid &&
      !entryArbiter.io.out.bits.needsLayerInfo) &&
    !entryArbiter.io.out.bits.isDone
  // If this is a transition into a layer which is not the first
  // layer, then the Transaction Table requests need to block
  // until the Register File has all valid data. [TODO] This is
  // a sub-optimal design choice as PEs should be allowed to
  // start before the Register File has _all_ of its valid data,
  // but I'm leaving this the way it is due to the lack of a
  // non-trivial path to add this functionality.
  when (isCacheReq) {
    table(entryArbiter.io.out.bits.tableIndex).waiting := Bool(true)
    when (entryArbiter.io.out.bits.isDone) {
      printf("[INFO] TTable entry for ASID/TID %x/%x is done\n",
        table(entryArbiter.io.out.bits.tableIndex).asid,
        table(entryArbiter.io.out.bits.tableIndex).tid);
      table(entryArbiter.io.out.bits.tableIndex).done := Bool(true)
    }
  }
  when (isPeReq) {
    table(entryArbiter.io.out.bits.tableIndex).currentNode :=
      table(entryArbiter.io.out.bits.tableIndex).currentNode + UInt(1)
    // [TODO] This currentNodeInLayer is always incremented and I
    // think this is okay as the value will be reset when a Layer
    // Info request gets serviced.
    table(entryArbiter.io.out.bits.tableIndex).currentNodeInLayer :=
      table(entryArbiter.io.out.bits.tableIndex).currentNodeInLayer + UInt(1)
    table(entryArbiter.io.out.bits.tableIndex).inFirst :=
      table(entryArbiter.io.out.bits.tableIndex).currentLayer === UInt(0)
    table(entryArbiter.io.out.bits.tableIndex).inLast :=
      table(entryArbiter.io.out.bits.tableIndex).currentLayer ===
      table(entryArbiter.io.out.bits.tableIndex).numLayers - UInt(1)
    // If we're at the end of a layer, we need new layer
    // information
    when(table(entryArbiter.io.out.bits.tableIndex).currentNodeInLayer ===
      // The comparison here differs from how this is handled in
      // nn_instruction.v.
      table(entryArbiter.io.out.bits.tableIndex).nodesInCurrentLayer - UInt(1) &&
      table(entryArbiter.io.out.bits.tableIndex).currentLayer <
      table(entryArbiter.io.out.bits.tableIndex).numLayers - UInt(1)
    ) {
      table(entryArbiter.io.out.bits.tableIndex).needsLayerInfo := Bool(true)
      table(entryArbiter.io.out.bits.tableIndex).currentLayer :=
        table(entryArbiter.io.out.bits.tableIndex).currentLayer + UInt(1)
    } .otherwise {
      table(entryArbiter.io.out.bits.tableIndex).needsLayerInfo := Bool(false)
      table(entryArbiter.io.out.bits.tableIndex).currentLayer :=
        table(entryArbiter.io.out.bits.tableIndex).currentLayer
    }}

  // Reset Condition
  when (reset) {for (i <- 0 until transactionTableNumEntries) {
    table(i).valid := Bool(false)
    table(i).reserved := Bool(false)}}

  // Assertions

  // The X-FILES arbiter should only receive a request if it is
  // asserting its ready signal.
  assert(!(io.arbiter.rocc.cmd.valid && cmd.readOrWrite && cmd.isNew &&
    !hasFree),
    "TTable saw new write req, but doesn't have any free entries")

  // Only one inbound request or response on the same line can
  // currently be handled. Due to the split nature of cache and
  // register file responses, both have to be checked.
  assert(!(io.arbiter.rocc.cmd.valid && cmd.readOrWrite && cmd.isNew &&
    io.control.resp.bits.cacheValid &&
    (io.control.resp.bits.tableIndex === nextFree)),
    "TTable saw new write req on same entry as control resp from Cache")
  assert(!(io.arbiter.rocc.cmd.valid && cmd.readOrWrite && cmd.isNew &&
    io.control.resp.bits.layerValid &&
    (io.control.resp.bits.layerValidIndex === nextFree)),
    "TTable saw new write req on same entry as control resp from Reg File")

  assert(!(io.arbiter.rocc.cmd.valid && cmd.readOrWrite && !cmd.isNew &&
    io.control.resp.bits.cacheValid &&
    (io.control.resp.bits.tableIndex === derefTidIndex)),
    "TTable saw non-new write req on same entry as control resp from Cache")
  assert(!(io.arbiter.rocc.cmd.valid && cmd.readOrWrite && !cmd.isNew &&
    io.control.resp.bits.layerValid &&
    (io.control.resp.bits.layerValidIndex === derefTidIndex)),
    "TTable saw non-new write req on same entry as control resp from Reg File")

  assert(!(io.arbiter.rocc.cmd.valid && !cmd.readOrWrite &&
    io.control.resp.bits.cacheValid &&
    (io.control.resp.bits.tableIndex === derefTidIndex)),
    "TTable saw read req on same entry as control resp from Cache")
  assert(!(io.arbiter.rocc.cmd.valid && !cmd.readOrWrite &&
    io.control.resp.bits.layerValid &&
    (io.control.resp.bits.layerValidIndex === derefTidIndex)),
    "TTable saw read req on same entry as control resp from Reg File")

  // Valid should never be true if reserved is not true
  for (i <- 0 until transactionTableNumEntries)
    assert(!table(i).valid || table(i).reserved,
      "Valid asserted with reserved de-asserted on TTable " + i)

  // A read request or a non-new write request should hit a valid
  // entry
  assert(!(!foundTid && io.arbiter.rocc.cmd.valid &&
    (!cmd.readOrWrite || (cmd.readOrWrite && !cmd.isNew))),
    "TTable saw read or non-new write req on a non-existent ASID/TID")
  // A new write request should not hit a tid
  assert(!(foundTid && io.arbiter.rocc.cmd.valid &&
    cmd.readOrWrite && cmd.isNew),
    "TTable saw new write req on an existing ASID/TID")

  // A response from the Control module should never be dually valid
  // in terms of actions on the same transaction table index. This
  // assertion is currently disabled as it *shouldn't* be a problem.
  // The only field updated by a response originating at the register
  // file is the waiting field. If this arrives at the same time as a
  // cache response, the correct value (from the register file) will
  // correctly overwrite that of the cache update.
  // assert(!(io.control.resp.valid &&
  //   io.control.resp.bits.cacheValid && io.control.resp.bits.layerValid &&
  //   (io.control.resp.bits.tableIndex === io.control.resp.bits.layerValidIndex)),
  //   "TTable received dually valid control response addressing same TID index")

  // A Control response should never have a cacheValid or layerValid
  // asserted when the decoupled valid is deasserted
  assert(!(!io.control.resp.valid &&
    (io.control.resp.bits.cacheValid || io.control.resp.bits.layerValid)),
    "TTable control response deasserted, but cacheValid or layerValid asserted")

  // The current node should never be greater than the total number of nodes
  assert(!Vec((0 until transactionTableNumEntries).map(i =>
    table(i).valid && (table(i).currentNode > table(i).numNodes))).contains(Bool(true)),
    "A TTable entry has a currentNode count greater than the total numNodes")

  // Don't send a response to the core unless it's ready
  assert(!(io.arbiter.rocc.resp.valid && !io.arbiter.rocc.resp.ready),
    "TTable tried to send a valid response when core was not ready")

  // Inbound read requests should only hit a done entry. [TODO] I'm
  // currently generating e_NOT_DONE responses when this happens.
  // Assertion is getting turned off, consequently.
  // assert(!(io.arbiter.rocc.cmd.valid && !cmd.readOrWrite &&
  //   !table(derefTidIndex).done),
  //   "TTable saw read request on entry that is not done")
}

class TransactionTableTests(uut: TransactionTable, isTrace: Boolean = true)
    extends DanaTester(uut, isTrace) {
  for (t <- 0 until 3) {
    peek(uut.hasFree)
    peek(uut.nextFree)
    val tid = t
    val nnid = t + 15 * 16
    // newWriteRequest(uut.io.arbiter.rocc, tid, nnid)
    // writeRndData(uut.io.arbiter.rocc, tid, nnid, 5, 10)
    info(uut)
    poke(uut.io.control.req.ready, 1)
  }
}