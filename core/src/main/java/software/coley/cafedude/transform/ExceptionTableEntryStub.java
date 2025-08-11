package software.coley.cafedude.transform;

import software.coley.cafedude.classfile.constant.CpClass;

final class ExceptionTableEntryStub {
	final Label start, end, handler;
	final CpClass catchType;

	ExceptionTableEntryStub(Label start, Label end, Label handler, CpClass catchType) {
		this.start = start;
		this.end = end;
		this.handler = handler;
		this.catchType = catchType;
	}
}
