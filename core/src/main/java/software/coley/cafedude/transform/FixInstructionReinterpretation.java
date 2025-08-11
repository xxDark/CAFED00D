package software.coley.cafedude.transform;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.coley.cafedude.classfile.ClassFile;
import software.coley.cafedude.classfile.ConstPool;
import software.coley.cafedude.classfile.attribute.CodeAttribute;
import software.coley.cafedude.classfile.instruction.BasicInstruction;
import software.coley.cafedude.classfile.instruction.Instruction;
import software.coley.cafedude.classfile.instruction.IntOperandInstruction;
import software.coley.cafedude.classfile.instruction.LookupSwitchInstruction;
import software.coley.cafedude.classfile.instruction.Opcodes;
import software.coley.cafedude.classfile.instruction.TableSwitchInstruction;
import software.coley.cafedude.io.IndexableByteStream;
import software.coley.cafedude.io.InstructionReader;
import software.coley.cafedude.io.InstructionWriter;
import software.coley.cafedude.util.GrowingByteBuffer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static software.coley.cafedude.classfile.instruction.Opcodes.*;

final class FixInstructionReinterpretation {
	private static final Logger logger = LoggerFactory.getLogger(FixInstructionReinterpretation.class);
	final ClassFile file;
	final ConstPool pool;
	final CodeAttribute code;
	List<Instruction> instructions;
	List<ExceptionTableEntryStub> exceptionTableEntries;
	Label[] labels;
	byte[] rawCode;

	FixInstructionReinterpretation(ClassFile file, ConstPool pool, CodeAttribute code) {
		this.file = file;
		this.pool = pool;
		this.code = code;
		int codeLength = 0;
		for (var instruction : code.getInstructions()) {
			codeLength += instruction.computeSize();
		}
		instructions = Arrays.asList(new Instruction[codeLength]);
	}

	private byte[] writeCode() {
		var rawCode = this.rawCode;
		if (rawCode == null) {
			rawCode = new InstructionWriter().writeCode(code.getInstructions());
			this.rawCode = rawCode;
		}
		return rawCode;
	}

	private void assign() {
		int offset = 0;
		for (var instruction : code.getInstructions()) {
			instructions.set(offset, instruction);
			offset += instruction.computeSize();
		}
	}

	private boolean reinterpret(int pc, int offset) {
		int dst = pc + offset;
		if (instructions.get(dst) == null) {
			byte[] bytes = writeCode();
			var stream = new IndexableByteStream(bytes);
			stream.moveTo(dst);
			boolean patched = false;
			try {
				var reader = new InstructionReader();
				loop:
				while (true) {
					// Try to fill in as much as possible,
					// until we hit either no control flow instruction or we
					// see another existing instruction.
					int current = stream.getIndex();
					if (current >= instructions.size() || instructions.get(current) != null)
						break;
					var list = reader.read(stream, pool, 1);
					if (list.isEmpty())
						break;
					if (list.size() != 1)
						throw new IllegalStateException("Must read exactly one instruction");
					var tmp = list.get(0);
					instructions.set(current, tmp);
					patched = true;
					switch (tmp.getOpcode()) {
						case JSR:
						case JSR_W:
						case GOTO:
						case GOTO_W:
						case LOOKUPSWITCH:
						case TABLESWITCH:
						case IRETURN:
						case LRETURN:
						case DRETURN:
						case ARETURN:
						case RETURN:
						case ATHROW:
							break loop;
					}
				}
			} catch (Throwable t) {
				// TODO: Once this pass is finalized, we will actually ignore this.
				//  - For now with the samples on-hand there are still some cases where they
				//    seemingly have the wrong offsets on reinterpreted 'goto' instructions.
				//  - These problems get ignored at runtime since they usually are within opaque-predicate
				//    dead code which prevents the VM from freaking out.
				logger.warn("Reinterpretation encountered an exception", t);
			}
			return patched;
		}
		return false;
	}

	private boolean reinterpretSwitch(int pc, int dflt, List<Integer> cases) {
		boolean reinterpreted = reinterpret(pc, dflt);
		for (var offset : cases) {
			reinterpreted |= reinterpret(pc, offset);
		}
		return reinterpreted;
	}

	private boolean reinterpret(int pc, Instruction instruction) {
		if (instruction instanceof LookupSwitchInstruction) {
			var lsw = (LookupSwitchInstruction) instruction;
			return reinterpretSwitch(pc, lsw.getDefault(), lsw.getOffsets());
		}
		if (instruction instanceof TableSwitchInstruction) {
			var tsw = (TableSwitchInstruction) instruction;
			return reinterpretSwitch(pc, tsw.getDefault(), tsw.getOffsets());
		}
		switch (instruction.getOpcode()) {
			case IFEQ:
			case IFNE:
			case IFLT:
			case IFGE:
			case IFGT:
			case IFLE:
			case IF_ICMPEQ:
			case IF_ICMPNE:
			case IF_ICMPLT:
			case IF_ICMPGE:
			case IF_ICMPGT:
			case IF_ICMPLE:
			case IF_ACMPEQ:
			case IF_ACMPNE:
			case IFNULL:
			case IFNONNULL:
			case GOTO:
			case JSR:
			case GOTO_W:
			case JSR_W:
				return reinterpret(pc, ((IntOperandInstruction) instruction).getOperand());
		}
		return false;
	}

	private boolean reinterpret() {
		boolean seen = false;
		var instructions = this.instructions;
		boolean hasWork;
		do {
			hasWork = false;
			for (int i = 0; i < instructions.size(); i++) {
				var insn = instructions.get(i);
				if (insn == null) continue;
				hasWork |= reinterpret(i, insn);
			}
			seen |= hasWork;
		} while (hasWork);
		return seen;
	}

	private Label createLabel(int pc) {
		Label[] labels = this.labels;
		Label lbl;
		if ((lbl = labels[pc]) == null) {
			labels[pc] = lbl = new Label(pc);
		}
		return lbl;
	}

	private Label createLabel(int pc, int offset) {
		pc += offset;
		return createLabel(pc);
	}

	private void rewrite() {
		labels = new Label[instructions.size()];
		var instructions = this.instructions;
		for (int pc = 0; pc < instructions.size(); pc++) {
			var insn = instructions.get(pc);
			if (insn == null) continue;
			var replacement = insn;
			switch (insn.getOpcode()) {
				case LOOKUPSWITCH: {
					var lsw = (LookupSwitchInstruction) insn;
					int thisPc = pc;
					replacement = new LookupSwitchStub(
							lsw.getPadding(),
							lsw.getKeys(),
							createLabel(thisPc, lsw.getDefault()),
							lsw.getOffsets().stream().map(offset -> createLabel(thisPc, offset)).toList()
					);
					break;
				}
				case TABLESWITCH: {
					var tsw = (TableSwitchInstruction) insn;
					int thisPc = pc;
					replacement = new TableSwitchStub(
							tsw.getPadding(),
							tsw.getLow(),
							tsw.getHigh(),
							createLabel(thisPc, tsw.getDefault()),
							tsw.getOffsets().stream().map(offset -> createLabel(thisPc, offset)).toList()
					);
					break;
				}
				case IFEQ:
				case IFNE:
				case IFLT:
				case IFGE:
				case IFGT:
				case IFLE:
				case IF_ICMPEQ:
				case IF_ICMPNE:
				case IF_ICMPLT:
				case IF_ICMPGE:
				case IF_ICMPGT:
				case IF_ICMPLE:
				case IF_ACMPEQ:
				case IF_ACMPNE:
				case IFNULL:
				case IFNONNULL:
				case GOTO:
				case JSR:
				case GOTO_W:
				case JSR_W:
					replacement = new JumpInstruction(insn.getOpcode(), createLabel(pc, ((IntOperandInstruction) insn).getOperand()));
			}
			instructions.set(pc, replacement);
		}
		var origTable = code.getExceptionTable();
		exceptionTableEntries = new ArrayList<>(origTable.size());
		for (var exceptionTableEntry : origTable) {
			exceptionTableEntries.add(new ExceptionTableEntryStub(
					createLabel(exceptionTableEntry.getStartPc()),
					createLabel(exceptionTableEntry.getEndPc()),
					createLabel(exceptionTableEntry.getHandlerPc()),
					exceptionTableEntry.getCatchType()
			));
		}
		var newList = new ArrayList<Instruction>(instructions.size());
		for (int i = 0; i < instructions.size(); i++) {
			var instruction = instructions.get(i);
			Label label = labels[i];
			if (label != null) {
				newList.add(label);
			}
			if (instruction == null) {
				instruction = new BasicInstruction(NOP);
			}
			newList.add(instruction);
		}
		this.instructions = newList;
		labels = null; // Not needed.
	}

	private void zap() {
		// TODO link all instructions in a linked list,
		// and filter out dead code.
		instructions.removeIf(instruction -> instruction.getOpcode() == NOP);
	}

	private int patchControlFlow(int pc, int index, JumpInstruction jmp) {
		int opcode = jmp.getOpcode();
		switch (opcode) {
			case GOTO_W:
			case JSR_W:
				return 0;
		}
		final int EXTRA_DISTANCE = 8;
		int dst = jmp.label.pc;
		int distance = dst - pc;
		if (distance > 0) {
			// if distance > 0, it means we jump forward, add extra bytes.
			distance += EXTRA_DISTANCE;
		} else if (distance < 0) {
			// if distance > 0, it means we jump backwards, subtract extra bytes.
			distance -= EXTRA_DISTANCE;
		}
		if (Math.abs(distance) <= 32767) {
			return 0;
		}
		switch (opcode) {
			case GOTO:
				instructions.set(index, new JumpInstruction(GOTO_W, jmp.label));
				return 0;
			case JSR:
				instructions.set(index, new JumpInstruction(JSR_W, jmp.label));
				return 0;
		}
		var jumpNotTaken = new Label(-1);
		var patch = new InstructionPatch();
		var patches = patch.patches;
		patches.add(new JumpInstruction(reverseOpcode(opcode), jumpNotTaken));
		patches.add(new JumpInstruction(GOTO_W, jmp.label));
		patches.add(jumpNotTaken);
		instructions.set(index, patch);
		return patches.size();
	}

	private int fixControlFlow() {
		// We now use normal label objects instead
		// of raw offsets, insert this sequence if jump distance may end up too large:
		// inverse(if_xxx) jump_not_taken
		// goto_w target
		// jump_not_taken:

		// compute worst-case scenario offsets for labels.
		{
			var instructions = this.instructions;
			int worstCasePc = 0;
			for (var instruction : instructions) {
				if (instruction instanceof Label) {
					((Label) instruction).pc = worstCasePc;
				}
				worstCasePc += worstCaseSize(instruction);
			}
		}
		int patchesAdded = 0;
		int pc = 0;
		for (int i = 0; i < instructions.size(); i++) {
			var insn = instructions.get(i);
			if (insn instanceof JumpInstruction) {
				patchesAdded += patchControlFlow(pc, i, (JumpInstruction) insn);
			}
			pc += worstCaseSize(insn);
		}
		return patchesAdded;
	}

	private void flushPatches(int patchesAdded) {
		var instructions = this.instructions;
		var copy = new ArrayList<Instruction>(instructions.size() + patchesAdded);
		for (var instruction : instructions) {
			if (instruction instanceof InstructionPatch) {
				copy.addAll(((InstructionPatch) instruction).patches);
			} else {
				copy.add(instruction);
			}
		}
		this.instructions = copy;
	}

	private void flushBytes() {
		record Fixup(int pc, int at, Label target) {

			Fixup(int pc, JumpInstruction jmp) {
				this(pc, pc + 1, jmp.label);
			}
		}
		var buffer = new GrowingByteBuffer();
		var fixups = new ArrayList<Fixup>();
		var writer = new InstructionWriter();
		for (var instruction : instructions) {
			int startPos = buffer.position();
			if (instruction instanceof Label) {
				((Label) instruction).pc = startPos;
				continue;
			} else if (instruction instanceof JumpInstruction) {
				fixups.add(new Fixup(startPos, (JumpInstruction) instruction));
				instruction = new IntOperandInstruction(instruction.getOpcode(), -1);
			} else if (instruction instanceof LookupSwitchStub) {
				var lsw = (LookupSwitchStub) instruction;
				int pc = startPos + 1;
				pc += 4 - pc & 3;
				fixups.add(new Fixup(startPos, pc, lsw.dflt));
				pc += 4; // skip dflt
				pc += 4; // skip keyCount
				for (var dst : lsw.cases) {
					pc += 4; // skip key
					fixups.add(new Fixup(startPos, pc, dst));
					pc += 4; // skip dst
				}
				var dummy = Collections.nCopies(lsw.cases.size(), -1);
				instruction = new LookupSwitchInstruction(
						-1,
						dummy,
						dummy
				);
			} else if (instruction instanceof TableSwitchStub) {
				var tsw = (TableSwitchStub) instruction;
				int pc = startPos + 1;
				pc += 4 - pc & 3;
				fixups.add(new Fixup(startPos, pc, tsw.dflt));
				pc += 4; // skip dflt
				pc += 4; // skip low
				pc += 4; // skip high
				for (var dst : tsw.cases) {
					fixups.add(new Fixup(startPos, pc, dst));
					pc += 4; // skip dst
				}
				var dummy = Collections.nCopies(tsw.cases.size(), -1);
				instruction = new TableSwitchInstruction(
						-1,
						tsw.low,
						tsw.high,
						dummy
				);
			}
			writer.write(buffer, instruction);
		}

		var bb = buffer.unwrap();
		for (var fixup : fixups) {
			int oldPos = buffer.position();
			buffer.position(fixup.at);
			int offset = fixup.target.pc - fixup.pc;
			switch ((bb.get(fixup.pc) & 0xFF)) {
				case GOTO_W:
				case JSR_W:
				case LOOKUPSWITCH:
				case TABLESWITCH:
					buffer.putInt(offset);
					break;
				default:
					buffer.putShort(offset);
			}
			buffer.position(oldPos);
		}
		var stream = new IndexableByteStream(bb.array());
		try {
			code.setInstructions(new InstructionReader().read(
					stream,
					pool,
					buffer.position()
			));
		} catch (Exception e) {
			throw new IllegalStateException("Error writing instructions", e);
		}
	}

	private void flushExceptionHandlers() {
		var exceptionTable = code.getExceptionTable();
		for (int i = 0; i < exceptionTable.size(); i++) {
			var orig = exceptionTable.get(i);
			var patched = exceptionTableEntries.get(i);
			orig.setStartPc(patched.start.pc);
			orig.setEndPc(patched.end.pc);
			orig.setHandlerPc(patched.handler.pc);
		}
	}

	private void flush(int patchesAdded) {
		flushPatches(patchesAdded);
		flushBytes();
		flushExceptionHandlers();
	}

	void doit() {
		assign();
		if (!reinterpret()) return;
		rewrite();
		zap();
		int patchesAdded = fixControlFlow();
		flush(patchesAdded);
	}

	private static int worstCaseSize(Instruction instruction) {
		switch (instruction.getOpcode()) {
			case GOTO:
			case JSR:
				return 5;
			case LOOKUPSWITCH:
				return instruction.computeSize() - ((LookupSwitchStub) instruction).padding + 4;
			case TABLESWITCH:
				return instruction.computeSize() - ((TableSwitchStub) instruction).padding + 4;
		}
		return instruction.computeSize();
	}

	private static int reverseOpcode(int opcode) {
		switch (opcode) {
			case IFNULL:
				return IFNONNULL;
			case IFNONNULL:
				return IFNULL;
			case IFEQ:
				return IFNE;
			case IFNE:
				return IFEQ;
			case IFLT:
				return IFGE;
			case IFGE:
				return IFLT;
			case IFGT:
				return IFLE;
			case IFLE:
				return IFGT;
			case IF_ICMPEQ:
				return IF_ICMPNE;
			case IF_ICMPNE:
				return IF_ICMPEQ;
			case IF_ICMPLT:
				return IF_ICMPGE;
			case IF_ICMPGE:
				return IF_ICMPLT;
			case IF_ICMPGT:
				return IF_ICMPLE;
			case IF_ICMPLE:
				return IF_ICMPGT;
			case IF_ACMPEQ:
				return IF_ACMPNE;
			case IF_ACMPNE:
				return IF_ACMPEQ;
			default:
				throw new IllegalArgumentException("Unknown opcode: " + opcode);
		}
	}
}
