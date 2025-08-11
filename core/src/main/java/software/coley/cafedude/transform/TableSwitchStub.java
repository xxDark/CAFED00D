package software.coley.cafedude.transform;

import software.coley.cafedude.classfile.instruction.BasicInstruction;
import software.coley.cafedude.classfile.instruction.Opcodes;

import java.util.List;

final class TableSwitchStub extends BasicInstruction {
	final int padding;
	final int low;
	final int high;
	final Label dflt;
	final List<Label> cases;

	TableSwitchStub(int padding, int low, int high, Label dflt, List<Label> cases) {
		super(Opcodes.TABLESWITCH);
		this.padding = padding;
		this.low = low;
		this.high = high;
		this.dflt = dflt;
		this.cases = cases;
	}

	@Override
	public int computeSize() {
		return 1 + padding + 4 + 4 + 4 + cases.size() * 4;
	}
}
