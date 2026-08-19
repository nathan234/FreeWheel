import ghidra.app.script.GhidraScript;
import ghidra.app.decompiler.*;
import ghidra.app.cmd.disassemble.DisassembleCommand;
import ghidra.app.cmd.function.CreateFunctionCmd;
import ghidra.program.model.address.*;
import ghidra.program.model.listing.Function;
import ghidra.program.model.symbol.SourceType;
import ghidra.util.task.ConsoleTaskMonitor;

public class DecompileTargets extends GhidraScript {
    long[][] R = {
        {0xf4acc8L,0xf4b42cL}, {0xf49c64L,0xf4a118L}, {0xf4a190L,0xf4a4ccL},
        {0xf4b42cL,0xf4b5e8L}, {0xf4bd6cL,0xf4c2d0L}, {0xf57500L,0xf5849cL},
        {0xf584d0L,0xf58b04L}, {0xf59d84L,0xf59e44L}, {0xf5a14cL,0xf5a62cL},
        {0xf488b0L,0xf4953cL}, {0xf5a660L,0xf5b834L}, {0xf616d8L,0xf620e8L}
    };
    String[] names = {
        "DefaultBMSGeneralInfoCompact_fromBytes","DefaultP6BMSAllRealTimeInfo_fromBytes",
        "DefaultBMSAllRealTimeInfo_fromBytes","DefaultBMSCellsVoltage_fromBytes",
        "DefaultBMSProtectionCompact_fromBytes","DefaultP6RealTimeInfo_fromBytes",
        "DefaultP6RealTimeState_fromBytes","DefaultP6VehicleExtendInfo_fromBytes",
        "DefaultP6SingleBatteryRealTimeInfo_fromBytes","DefaultCommonVehicleRealTimeInfo_fromBytes",
        "P6SettingsDefault_fromBytes","V11SettingsDefault_fromBytes"
    };

    public void run() throws Exception {
        DecompInterface d = new DecompInterface();
        d.openProgram(currentProgram);
        ConsoleTaskMonitor m = new ConsoleTaskMonitor();
        Address base = currentProgram.getImageBase();
        for (int i = 0; i < R.length; i++) {
            Address s = base.add(R[i][0]);
            Address last = base.add(R[i][1] - 1);
            AddressSet range = new AddressSet(s, last);
            println("\n===== BEGIN " + names[i] + " @ 0x" + Long.toHexString(R[i][0]) + " =====");
            clearListing(range);                              // .text is loaded as data; make it undefined
            DisassembleCommand dc = new DisassembleCommand(s, range, true);
            dc.applyTo(currentProgram, m);
            Function fn = getFunctionAt(s);
            if (fn == null) {
                CreateFunctionCmd fc = new CreateFunctionCmd(names[i], s, range, SourceType.USER_DEFINED);
                fc.applyTo(currentProgram, m);
                fn = getFunctionAt(s);
                if (fn == null) { println("!! createFn: " + fc.getStatusMsg()); println("===== END ====="); continue; }
            }
            DecompileResults r = d.decompileFunction(fn, 180, m);
            if (r == null || !r.decompileCompleted())
                println("!! decompile failed: " + (r==null?"null":r.getErrorMessage()));
            else
                println(r.getDecompiledFunction().getC());
            println("===== END " + names[i] + " =====");
        }
        println("\n##### DECOMPILE RUN COMPLETE #####");
    }
}
