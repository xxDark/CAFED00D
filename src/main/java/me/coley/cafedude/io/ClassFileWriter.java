package me.coley.cafedude.io;

import me.coley.cafedude.classfile.ConstantPoolConstants;
import me.coley.cafedude.classfile.ClassFile;
import me.coley.cafedude.classfile.Field;
import me.coley.cafedude.InvalidClassException;
import me.coley.cafedude.classfile.Method;
import me.coley.cafedude.classfile.attribute.Attribute;
import me.coley.cafedude.classfile.constant.ConstPoolEntry;
import me.coley.cafedude.classfile.constant.ConstRef;
import me.coley.cafedude.classfile.constant.CpClass;
import me.coley.cafedude.classfile.constant.CpDouble;
import me.coley.cafedude.classfile.constant.CpDynamic;
import me.coley.cafedude.classfile.constant.CpFloat;
import me.coley.cafedude.classfile.constant.CpInt;
import me.coley.cafedude.classfile.constant.CpInvokeDynamic;
import me.coley.cafedude.classfile.constant.CpLong;
import me.coley.cafedude.classfile.constant.CpMethodHandle;
import me.coley.cafedude.classfile.constant.CpMethodType;
import me.coley.cafedude.classfile.constant.CpModule;
import me.coley.cafedude.classfile.constant.CpNameType;
import me.coley.cafedude.classfile.constant.CpPackage;
import me.coley.cafedude.classfile.constant.CpString;
import me.coley.cafedude.classfile.constant.CpUtf8;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/**
 * Class file format writer.
 *
 * @author Matt Coley
 * @see ClassFile Parsed class representation.
 * @see ClassFileWriter Class file format writer.
 */
public class ClassFileWriter {
	private DataOutputStream out;
	private AttributeWriter attributeWriter;

	/**
	 * @param clazz
	 * 		Parsed class file.
	 *
	 * @return Bytecode of class.
	 *
	 * @throws InvalidClassException
	 * 		When the class cannot be written.
	 */
	public byte[] write(ClassFile clazz) throws InvalidClassException {
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		try (DataOutputStream out = new DataOutputStream(baos)) {
			this.out = out;
			attributeWriter = new AttributeWriter(clazz);
			// Write magic header
			out.writeInt(0xCAFEBABE);
			// Version
			out.writeShort(clazz.getVersionMinor());
			out.writeShort(clazz.getVersionMajor());
			// Constant pool
			out.writeShort(clazz.getPool().size() + 1);
			for (ConstPoolEntry entry : clazz.getPool())
				writeCpEntry(entry);
			// Flags
			out.writeShort(clazz.getAccess());
			// This/super classes
			out.writeShort(clazz.getClassIndex());
			out.writeShort(clazz.getSuperIndex());
			// Interfaces
			out.writeShort(clazz.getInterfaceIndices().size());
			for (int interfaceIdx : clazz.getInterfaceIndices())
				out.writeShort(interfaceIdx);
			// Fields
			out.writeShort(clazz.getFields().size());
			for (Field field : clazz.getFields())
				writeField(field);
			// Methods
			out.writeShort(clazz.getMethods().size());
			for (Method method : clazz.getMethods())
				writeMethod(method);
			// Attributes
			out.writeShort(clazz.getAttributes().size());
			for (Attribute attribute : clazz.getAttributes())
				writeAttribute(attribute);
			return baos.toByteArray();
		} catch (IOException ex) {
			throw new InvalidClassException(ex);
		}
	}

	/**
	 * @param entry
	 * 		Constant pool entry to write.
	 *
	 * @throws IOException
	 * 		When the stream cannot be written to.
	 * @throws InvalidClassException
	 * 		When the class has unexpected data.
	 */
	private void writeCpEntry(ConstPoolEntry entry) throws IOException, InvalidClassException {
		int tag = entry.getTag();
		out.writeByte(tag);
		switch (tag) {
			case ConstantPoolConstants.UTF8:
				out.writeUTF(((CpUtf8) entry).getText());
				break;
			case ConstantPoolConstants.INTEGER:
				out.writeInt(((CpInt) entry).getValue());
				break;
			case ConstantPoolConstants.FLOAT:
				out.writeFloat(((CpFloat) entry).getValue());
				break;
			case ConstantPoolConstants.LONG:
				out.writeLong(((CpLong) entry).getValue());
				break;
			case ConstantPoolConstants.DOUBLE:
				out.writeDouble(((CpDouble) entry).getValue());
				break;
			case ConstantPoolConstants.STRING:
				out.writeShort(((CpString) entry).getIndex());
				break;
			case ConstantPoolConstants.CLASS:
				out.writeShort(((CpClass) entry).getIndex());
				break;
			case ConstantPoolConstants.FIELD_REF:
			case ConstantPoolConstants.METHOD_REF:
			case ConstantPoolConstants.INTERFACE_METHOD_REF:
				out.writeShort(((ConstRef) entry).getClassIndex());
				out.writeShort(((ConstRef) entry).getNameTypeIndex());
				break;
			case ConstantPoolConstants.NAME_TYPE:
				out.writeShort(((CpNameType) entry).getNameIndex());
				out.writeShort(((CpNameType) entry).getTypeIndex());
				break;
			case ConstantPoolConstants.DYNAMIC:
				out.writeShort(((CpDynamic) entry).getBsmIndex());
				out.writeShort(((CpDynamic) entry).getNameTypeIndex());
				break;
			case ConstantPoolConstants.METHOD_HANDLE:
				out.writeByte(((CpMethodHandle) entry).getKind());
				out.writeShort(((CpMethodHandle) entry).getReferenceIndex());
				break;
			case ConstantPoolConstants.METHOD_TYPE:
				out.writeShort(((CpMethodType) entry).getIndex());
				break;
			case ConstantPoolConstants.INVOKE_DYNAMIC:
				out.writeShort(((CpInvokeDynamic) entry).getBsmIndex());
				out.writeShort(((CpInvokeDynamic) entry).getNameTypeIndex());
				break;
			case ConstantPoolConstants.MODULE:
				out.writeShort(((CpModule) entry).getIndex());
				break;
			case ConstantPoolConstants.PACKAGE:
				out.writeShort(((CpPackage) entry).getIndex());
				break;
			default:
				throw new InvalidClassException("Unknown constant-pool tag: " + tag);
		}
	}

	/**
	 * @param attribute
	 * 		Attribute to write.
	 *
	 * @throws IOException
	 * 		When the stream cannot be written to.
	 * @throws InvalidClassException
	 * 		When the attribute name points to a non-utf8
	 * 		constant.
	 */
	private void writeAttribute(Attribute attribute) throws IOException, InvalidClassException {
		out.write(attributeWriter.writeAttribute(attribute));
	}

	/**
	 * @param field
	 * 		Field to write.
	 *
	 * @throws IOException
	 * 		When the stream cannot be written to.
	 * @throws InvalidClassException
	 * 		When an attached attribute is invalid.
	 */
	private void writeField(Field field) throws IOException, InvalidClassException {
		out.writeShort(field.getAccess());
		out.writeShort(field.getNameIndex());
		out.writeShort(field.getTypeIndex());
		out.writeShort(field.getAttributes().size());
		for (Attribute attribute : field.getAttributes())
			writeAttribute(attribute);
	}

	/**
	 * @param method
	 * 		Method to write.
	 *
	 * @throws IOException
	 * 		When the stream cannot be written to.
	 * @throws InvalidClassException
	 * 		When an attached attribute is invalid.
	 */
	private void writeMethod(Method method) throws IOException, InvalidClassException {
		out.writeShort(method.getAccess());
		out.writeShort(method.getNameIndex());
		out.writeShort(method.getTypeIndex());
		out.writeShort(method.getAttributes().size());
		for (Attribute attribute : method.getAttributes())
			writeAttribute(attribute);
	}
}
