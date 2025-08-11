package software.coley.cafedude.transform;

import software.coley.cafedude.classfile.instruction.BasicInstruction;

final class Label extends BasicInstruction {
	int pc;

	Label(int pc) {
		super(-1);
		this.pc = pc;
	}

	@Override
	public int computeSize() {
		return 0;
	}
}
