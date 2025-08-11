package software.coley.cafedude.transform;

import software.coley.cafedude.classfile.ClassFile;
import software.coley.cafedude.classfile.ConstPool;
import software.coley.cafedude.classfile.attribute.CodeAttribute;
import software.coley.cafedude.classfile.instruction.IntOperandInstruction;
import software.coley.cafedude.classfile.instruction.LookupSwitchInstruction;
import software.coley.cafedude.classfile.instruction.TableSwitchInstruction;
import software.coley.cafedude.io.IndexableByteStream;
import software.coley.cafedude.io.InstructionReader;
import software.coley.cafedude.io.InstructionWriter;

import java.io.IOException;
import java.util.Arrays;
import java.util.BitSet;

import static software.coley.cafedude.classfile.instruction.Opcodes.*;

final class RemoveDeadInstructions {
	final ClassFile file;
	final ConstPool pool;
	final CodeAttribute code;
	final BitSet visited;
	final byte[] rawCode;
	final InstructionReader reader;

	RemoveDeadInstructions(ClassFile file, ConstPool pool, CodeAttribute code) {
		this.file = file;
		this.pool = pool;
		this.code = code;
		rawCode = new InstructionWriter().writeCode(code.getInstructions());
		visited = new BitSet(rawCode.length);
		reader = new InstructionReader(new IllegalRewritingInstructionsReader(
				pool,
				file.getVersionMajor()
		));
	}

	private void visit(int pc) throws Exception {
		var stream = new IndexableByteStream(rawCode);
		stream.moveTo(pc);
		do {
			pc = stream.getIndex();
			if (pc < 0 || pc >= rawCode.length) return;
			if (visited.get(pc)) return;
			var instruction = reader.read(stream, pool, 1).get(0);
			visited.set(pc, stream.getIndex());
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
					visit(pc + ((IntOperandInstruction) instruction).getOperand());
					break;
				case GOTO:
				case JSR:
				case GOTO_W:
				case JSR_W: {
					visit(pc + ((IntOperandInstruction) instruction).getOperand());
					return;
				}
				case LOOKUPSWITCH: {
					var lsw = (LookupSwitchInstruction) instruction;
					visit(pc + lsw.getDefault());
					for (var offset : lsw.getOffsets()) {
						visit(pc + offset);
					}
					return;
				}
				case TABLESWITCH: {
					var lsw = (TableSwitchInstruction) instruction;
					visit(pc + lsw.getDefault());
					for (var offset : lsw.getOffsets()) {
						visit(pc + offset);
					}
					return;
				}
				case IRETURN:
				case LRETURN:
				case FRETURN:
				case DRETURN:
				case ARETURN:
				case RETURN:
				case ATHROW:
				case RET:
					return;
			}
		} while (true);
	}

	private void discover() throws Exception {
		visit(0);
		for (var entry : code.getExceptionTable()) {
			if (!visited.get(entry.getStartPc()))
				continue;
			visit(entry.getHandlerPc());
		}
	}

	private void markDeadRegion(int from, int to) {
		Arrays.fill(rawCode, from, --to, (byte) NOP);
		rawCode[to] = (byte) ATHROW;
		code.getExceptionTable().add(new CodeAttribute.ExceptionTableEntry(
				from,
				to,
				to,
				null
		));
	}

	private void markDeadRegions() throws IOException {
		var rawCode = this.rawCode;
		var visited = this.visited;
		var original = true;
		for (int i = 0; i < rawCode.length;) {
			if (visited.get(i)) {
				i++;
				continue;
			}
			original = false;
			int setBit = visited.nextSetBit(i);
			if (setBit == -1) {
				markDeadRegion(i, rawCode.length);
				return;
			}
			markDeadRegion(i, setBit);
			i = setBit;
		}
		if (original)
			return;
		code.setInstructions(new InstructionReader().read(
				new IndexableByteStream(rawCode),
				pool,
				rawCode.length
		));
	}

	void doit() throws Exception {
		discover();
		markDeadRegions();
	}
}
