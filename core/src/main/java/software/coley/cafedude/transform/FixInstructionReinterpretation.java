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
import software.coley.cafedude.classfile.instruction.TableSwitchInstruction;
import software.coley.cafedude.io.IndexableByteStream;
import software.coley.cafedude.io.InstructionReader;
import software.coley.cafedude.io.InstructionWriter;
import software.coley.cafedude.util.GrowingByteBuffer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

import static software.coley.cafedude.classfile.instruction.Opcodes.*;

final class FixInstructionReinterpretation {
	private static final int INVALID_PC = Integer.MIN_VALUE;
	private static final int PATCHED_PC = Integer.MAX_VALUE;
	private static final int PATCH_CANARY = 0xcccc;
	private static final int PATCH_CANARY_W = 0xcccccccc;
	private static final Logger logger = LoggerFactory.getLogger(FixInstructionReinterpretation.class);
	final ClassFile file;
	final ConstPool pool;
	final CodeAttribute code;
	final BitSet real = new BitSet();
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
			real.set(offset);
			instructions.set(offset, instruction);
			offset += instruction.computeSize();
		}
	}

	private boolean reinterpret(int pc, int offset) {
		int dst = pc + offset;
		if (dst >= instructions.size()) return false;
		if (instructions.get(dst) == null) {
			byte[] bytes = writeCode();
			var stream = new IndexableByteStream(bytes);
			stream.moveTo(dst);
			boolean patched = false;
			try {
				var reader = new InstructionReader(new IllegalRewritingInstructionsReader(
						pool,
						file.getVersionMajor()
				));
				loop:
				while (true) {
					// Try to fill in as much as possible,
					// until we hit either no control flow instruction or we
					// see another existing instruction.
					int current = stream.getIndex();
					if (current >= instructions.size())
						break;
					Instruction instruction;
					try {
						instruction = reader.read(stream, pool, 1).get(0);
					} catch (Exception ex) {
						logger.warn("Error reading instruction", ex);
						return patched;
					}
					var previous = instructions.set(current, instruction);
					if (previous != null) {
						if (previous.getOpcode() != instruction.getOpcode()) {
							throw new IllegalStateException("Accidental instruction overwrite");
						}
						break;
					}
					real.set(current);
					patched = true;
					switch (instruction.getOpcode()) {
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
						case RET:
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

	private boolean reinterpret(int pc) {
		boolean seen = false;
		var instructions = this.instructions;
		boolean hasWork;
		do {
			hasWork = false;
			for (; pc < instructions.size(); pc++) {
				var insn = instructions.get(pc);
				if (insn == null) continue;
				hasWork |= reinterpret(pc, insn);
			}
			seen |= hasWork;
		} while (hasWork);
		return seen;
	}

	private boolean reinterpret() {
		var ok = reinterpret(0);
		for (var entry : code.getExceptionTable()) {
			ok |= reinterpret(entry.getHandlerPc(), 0);
		}
		return ok;
	}

	private Label createLabel(int pc) {
		Label[] labels = this.labels;
		if (pc < 0 || pc >= labels.length) {
			return new Label(INVALID_PC);
		}
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
					replacement = new JumpStub(insn.getOpcode(), createLabel(pc, ((IntOperandInstruction) insn).getOperand()));
			}
			instructions.set(pc, replacement);
		}
		{
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
		}
		// Jump over newly inserted code:
		// iconst_5
		// [reinterpreted]
		// pop
		// |
		// V
		// iconst_5
		// goto_w continue
		// [rienterpreted]
		// continue:
		// pop
		loop:
		for (int i = real.nextSetBit(0);;) {
			int pc = i;
			i = real.nextSetBit(i + 1);
			if (i == -1) break;
			var insn = Objects.requireNonNull(instructions.get(pc));
			for (int j = pc + 1; j < i; j++) {
				if (instructions.get(j) != null) {
					var dst = createLabel(i);
					var patch = new InstructionPatch(List.of(
							insn,
							new JumpStub(GOTO_W, dst)
					));
					instructions.set(pc, patch);
					continue loop;
				}
			}
		}
		// Form new list without gaps.
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

	private void fixJump(Label label) {
		if (label.pc != INVALID_PC) return;
		label.pc = PATCHED_PC;
		instructions.add(label);
		instructions.add(new BasicInstruction(ACONST_NULL));
		instructions.add(new BasicInstruction(ATHROW));
	}

	private void zap() {
		// Filter out dead code.
		instructions.removeIf(instruction -> instruction.getOpcode() == NOP);
		// Point dead targets to ACONST_NULL + ATHROW.
		for (int i = instructions.size(); i != 0; ) {
			var insn = instructions.get(--i);
			if (insn instanceof JumpStub) {
				fixJump(((JumpStub) insn).label);
				continue;
			}
			if (insn instanceof LookupSwitchStub) {
				var lsw = (LookupSwitchStub) insn;
				fixJump(lsw.dflt);
				for (var lbl : lsw.cases) {
					fixJump(lbl);
				}
				continue;
			}
			if (insn instanceof TableSwitchStub) {
				var tsw = (TableSwitchStub) insn;
				fixJump(tsw.dflt);
				for (var lbl : tsw.cases) {
					fixJump(lbl);
				}
			}
		}
	}

	private int patchControlFlow(int pc, int index, JumpStub jmp) {
		int opcode = jmp.getOpcode();
		switch (opcode) {
			case GOTO_W:
			case JSR_W:
				return 0;
		}
		final int EXTRA_DISTANCE = 8;
		int dst = jmp.label.pc;
		int distance = Math.abs(dst - pc) + EXTRA_DISTANCE;
		if (distance <= 32767) {
			return 0;
		}
		switch (opcode) {
			case GOTO:
				instructions.set(index, new JumpStub(GOTO_W, jmp.label));
				return 0;
			case JSR:
				instructions.set(index, new JumpStub(JSR_W, jmp.label));
				return 0;
		}
		var jumpNotTaken = new Label(-1);
		var patch = new InstructionPatch(List.of(
				new JumpStub(reverseOpcode(opcode), jumpNotTaken),
				new JumpStub(GOTO_W, jmp.label),
				jumpNotTaken
		));
		instructions.set(index, patch);
		return patch.patches.size();
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
		// Patch jumps with 16-bit offsets.
		int patchesAdded = 0;
		int pc = 0;
		for (int i = 0; i < instructions.size(); i++) {
			var insn = instructions.get(i);
			if (insn instanceof JumpStub) {
				patchesAdded += patchControlFlow(pc, i, (JumpStub) insn);
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

			Fixup(int pc, JumpStub jmp) {
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
			} else if (instruction instanceof JumpStub) {
				fixups.add(new Fixup(startPos, (JumpStub) instruction));
				int opcode = instruction.getOpcode();
				instruction = new IntOperandInstruction(opcode, opcode == GOTO_W || opcode == JSR_W ? PATCH_CANARY_W : PATCH_CANARY);
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
				var dummy = Collections.nCopies(lsw.cases.size(), PATCH_CANARY_W);
				instruction = new LookupSwitchInstruction(
						PATCH_CANARY_W,
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
				var dummy = Collections.nCopies(tsw.cases.size(), PATCH_CANARY_W);
				instruction = new TableSwitchInstruction(
						PATCH_CANARY_W,
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
					if (bb.getInt(fixup.at) != PATCH_CANARY_W) {
						throw new IllegalStateException("Wrong patching location");
					}
					bb.putInt(fixup.at, offset);
					break;
				default:
					if (offset < Short.MIN_VALUE || offset > Short.MAX_VALUE) {
						throw new IllegalStateException("fixControlFlow failed");
					}
					if ((bb.getShort(fixup.at) & 0xFFFF) != PATCH_CANARY) {
						throw new IllegalStateException("Wrong patching location");
					}
					bb.putShort(fixup.at, (short) offset);
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
		for (int i = exceptionTable.size(); i < exceptionTableEntries.size(); i++) {
			var patched = exceptionTableEntries.get(i);
			exceptionTable.add(new CodeAttribute.ExceptionTableEntry(
					patched.start.pc,
					patched.end.pc,
					patched.handler.pc,
					patched.catchType
			));
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
