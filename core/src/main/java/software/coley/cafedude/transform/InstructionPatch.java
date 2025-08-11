package software.coley.cafedude.transform;

import software.coley.cafedude.classfile.instruction.BasicInstruction;
import software.coley.cafedude.classfile.instruction.Instruction;

import java.util.ArrayList;
import java.util.List;

final class InstructionPatch extends BasicInstruction {
	final List<Instruction> patches = new ArrayList<>(4);

	 InstructionPatch() {
		super(-1);
	}
}
