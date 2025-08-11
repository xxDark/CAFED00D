package software.coley.cafedude.transform;

import software.coley.cafedude.classfile.instruction.BasicInstruction;
import software.coley.cafedude.classfile.instruction.Opcodes;

import java.util.List;

final class LookupSwitchStub extends BasicInstruction {
	final int padding;
	final List<Integer> keys;
	final Label dflt;
	final List<Label> cases;

	LookupSwitchStub(int padding, List<Integer> keys, Label dflt, List<Label> cases) {
		super(Opcodes.LOOKUPSWITCH);
		this.padding = padding;
		this.keys = keys;
		this.dflt = dflt;
		this.cases = cases;
	}

	@Override
	public int computeSize() {
		return 1 + padding + 4 + 4 + 4 * keys.size() + 4 * cases.size();
	}
}
