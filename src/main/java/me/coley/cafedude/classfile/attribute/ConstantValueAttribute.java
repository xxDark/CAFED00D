package me.coley.cafedude.classfile.attribute;

import java.util.Set;

/**
 * Constant value attribute
 *
 * @author JCWasmx86
 */
public class ConstantValueAttribute extends Attribute {
	private int constantValueIndex;

	/**
	 * @param nameIndex
	 * 		Name index in constant pool.
	 * @param constantValueIndex
	 * 		Index in the constant pool representing the value of this attribute.
	 */
	public ConstantValueAttribute(int nameIndex, int constantValueIndex) {
		super(nameIndex);
		this.constantValueIndex = constantValueIndex;
	}

	@Override
	public Set<Integer> cpAccesses() {
		Set<Integer> set = super.cpAccesses();
		set.add(getConstantValueIndex());
		return set;
	}

	@Override
	public int computeInternalLength() {
		return 2;
	}

	/**
	 * @return Index in the constant pool representing the value of this attribute.
	 */
	public int getConstantValueIndex() {
		return constantValueIndex;
	}

	/**
	 * @param constantValueIndex
	 * 		Index in the constant pool representing the value of this attribute.
	 */
	public void setConstantValueIndex(int constantValueIndex) {
		this.constantValueIndex = constantValueIndex;
	}
}
