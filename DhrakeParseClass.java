//Creates structs based on Delphi type information.
//@category Delphi
//@author Jesko Huettenhain
//@keybinding F8

import ghidra.app.script.GhidraScript;
import ghidra.app.util.NamespaceUtils;
import ghidra.program.model.address.Address;
import ghidra.program.model.data.*;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.GhidraClass;
import ghidra.program.model.mem.MemoryAccessException;
import ghidra.program.model.symbol.SourceType;
import ghidra.program.model.symbol.Symbol;
import ghidra.util.exception.DuplicateNameException;

public class DhrakeParseClass extends GhidraScript {

	protected DataType putType(DataType dt, DataTypeConflictHandler h) {
		return this.getCurrentProgram().getDataTypeManager().addDataType(dt, h);
	}

	protected DataType putType(DataType dt) {
		return this.putType(dt, DataTypeConflictHandler.REPLACE_HANDLER);
	}

	protected DataType addFnType(Function fn) {
		FunctionDefinitionDataType ft = new FunctionDefinitionDataType(fn, false);
		return this.putType(ft, DataTypeConflictHandler.KEEP_HANDLER);
	}

	protected void log(String message) {
		this.println(String.format("[Dhrake] %s", message));
	}

	private void log(String message, Object... args) {
		this.println(String.format("[Dhrake] %s", String.format(message, args)));
	}

	@Override
	protected void run() throws Exception {

		String  className   = null;
		Address nameAddress = this.toAddr(this.getInt(currentAddress.add(32)));

		try {
			this.clearListing(nameAddress, nameAddress.add(1));
			this.createData(nameAddress, PascalString255DataType.dataType);
			className = new String(this.getBytes(nameAddress.add(1), this.getByte(nameAddress)));
		} catch (MemoryAccessException e) {
			this.popup("Unfortunately, we got a memory access error trying to even read the class name. " +
							   "Either you executed this at the wrong address or the class metadata layout is " +
							   "unknown. If you can figure out how to identify this Delphi compiler and how it " +
							   "stores class metadata, that'd be Bill-Lumbergh-Level great.");
			return;
		}

		if (!className.toUpperCase().startsWith("T") && !className.toUpperCase().startsWith("E")) {
			this.log("Expected class to be prefixed with T or E, class name given was %s.", className);
			return;
		}

		// Create Data Structures + Methods
		this.log("Creating class %s", className);

		GhidraClass classNamespace;

		try {
			classNamespace = NamespaceUtils.convertNamespaceToClass(currentProgram
				.getSymbolTable()
				.getOrCreateNameSpace(
					currentProgram.getGlobalNamespace(),
					className,
					SourceType.USER_DEFINED
				)
			);
		} catch (DuplicateNameException e) {
			this.log("Class %s already exists, stopping", className);
			return;
		}

		if (classNamespace == null) {
			return;
		}

		StructureDataType base = new StructureDataType(className, 0);
		StructureDataType vtbl = new StructureDataType(className + "VT", 0);

		long vt = this.getInt(currentAddress);

		for (long tooDamnHigh = vt + 4 * 100; vt < tooDamnHigh; vt += 4) {
			try {

				long     offset     = this.getInt(this.toAddr(vt));
				String   name       = null;
				Address  entryPoint = this.toAddr(offset);
				Function function   = this.getFunctionAt(entryPoint);

				if (function == null) {

					Symbol funcSymbol = this.getSymbolAt(entryPoint);

					if (funcSymbol != null) {
						name = funcSymbol.toString();
					} else {
						name = String.format("FUN_%08X", offset);
					}

					this.log("defining function at 0x%08X, name %s", offset, name);
					function = this.createFunction(entryPoint, name);

				}

				if (function == null) {
					break;
				}

				function.setParentNamespace(classNamespace);
				name = function.getName();
				this.log("adding function %s::%s", className, name);
				vtbl.add(new Pointer32DataType(this.addFnType(function)), name, "");

			} catch (Exception e) {

				this.log("Failed to add function: %s", e);

				for (StackTraceElement element : e.getStackTrace()) {
					this.log("\t%s", element);
				}

				break;

			}
		}

		base.add(this.getCurrentProgram().getDataTypeManager().getPointer(this.putType(vtbl)), "vt", "Virtual Function Table");
		this.putType(base);

	}
}
