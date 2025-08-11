package software.coley.cafedude.classfile.instruction;

import jakarta.annotation.Nonnull;
import software.coley.cafedude.classfile.constant.CpEntry;

/**
 * Instruction that references a constant pool entry.
 *
 * @author Justus Garbe
 */
public class InvokeDynamicInstruction extends CpRefInstruction {
	private int padding;

	/**
	 * @param entry
	 * 		Constant pool entry to reference.
	 * @param padding
	 *      Instruction padding.
	 */
	public InvokeDynamicInstruction(@Nonnull CpEntry entry, int padding) {
		super(Opcodes.INVOKEDYNAMIC, entry);
		this.padding = padding;
	}

	public int getPadding() {
		return padding;
	}

	public void setPadding(int padding) {
		this.padding = padding;
	}
}
