package software.coley.cafedude.transform;

import software.coley.cafedude.classfile.instruction.BasicInstruction;
import software.coley.cafedude.classfile.instruction.Opcodes;

final class JumpStub extends BasicInstruction {
	Label label;

	JumpStub(int opcode, Label label) {
		super(opcode);
		this.label = label;
	}

	@Override
	public int computeSize() {
		switch (getOpcode()) {
			case Opcodes.GOTO_W:
			case Opcodes.JSR_W:
				return 5;
			default:
				return 3;
		}
	}
}
