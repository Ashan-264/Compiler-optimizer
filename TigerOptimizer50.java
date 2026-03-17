import ir.*;
import ir.operand.*;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.util.*;

public class TigerOptimizer
{
    public static void main(String[] args)
    {
        if (args.length != 2)
        {
            System.err.println("Usage: java TigerOptimizer <input_ir> <output_ir>"); // print error if wrong args
            System.exit(1);
        }

        String input_file = args[0];  // get input file path
        String output_file = args[1]; // get output file path

        try
        {
            IRReader reader = new IRReader(); // intialize reader
            IRProgram program = reader.parseIRFile(input_file); // parse the ir file into program struct

            for (IRFunction function : program.functions)
            {
                List<List<IRInstruction>> basic_blocks = form_basic_blocks(function.instructions); // split into basic blocks
                List<IRInstruction> optimized_func_instrs = new ArrayList<>(); // hold final optimized instructions

                for (List<IRInstruction> block : basic_blocks)
                {
                    List<IRInstruction> optimized_block = optimize_block(block, function); // optimize each block
                    optimized_func_instrs.addAll(optimized_block); // add optimized block to final list
                }

                function.instructions = optimized_func_instrs; // overwrite old instrs with new ones
            }

            PrintStream ps = new PrintStream(new FileOutputStream(output_file)); // open output stream
            IRPrinter printer = new IRPrinter(ps); // intialize printer
            printer.printProgram(program); // print the optimized ir
            ps.close(); // close stream
        }
        catch (Exception e)
        {
            e.printStackTrace();
            System.exit(1);
        }
    }

    private static List<List<IRInstruction>> form_basic_blocks(List<IRInstruction> instructions)
    {
        List<List<IRInstruction>> blocks = new ArrayList<>();
        List<IRInstruction> current_block = new ArrayList<>();

        for (IRInstruction inst : instructions)
        {
            if (inst.opCode == IRInstruction.OpCode.LABEL && !current_block.isEmpty())
            { // if label, close block and start new
                blocks.add(current_block);
                current_block = new ArrayList<>();
            }

            current_block.add(inst); // add instruction to block

            String op_name = inst.opCode.toString();
            if (op_name.startsWith("br") || op_name.equals("goto") || op_name.equals("return"))
            { // if branch or return, close block 
                blocks.add(current_block);
                current_block = new ArrayList<>();
            }
        }

        if (!current_block.isEmpty())
        { // catch the last block if not empty
            blocks.add(current_block);
        }

        return blocks;
    }

    private static List<IRInstruction> optimize_block(List<IRInstruction> block, IRFunction function)
    {
        Set<String> live_set = new HashSet<>(); // intialize work list

        for (IRVariableOperand var : function.variables)
        {
            live_set.add(var.getName()); // assume all vars are live at end of block
        }
        for (IRVariableOperand param : function.parameters)
        {
            live_set.add(param.getName()); // assume all params are live too
        }

        List<IRInstruction> optimized_block = new ArrayList<>();

        for (int i = block.size() - 1; i >= 0; i--)
        { // scan backwards from last instruction
            IRInstruction inst = block.get(i);

            String written_var = get_written_var(inst); // check what it writes to
            List<String> read_vars = get_read_vars(inst); // check what it reads from

            if (written_var != null && !live_set.contains(written_var))
            { // if writes to var not in live set, its dead code
                if (inst.opCode != IRInstruction.OpCode.CALLR && 
                    inst.opCode != IRInstruction.OpCode.CALL && 
                    inst.opCode != IRInstruction.OpCode.ARRAY_STORE)
                { // nerver discard a call or array store
                    continue; // skip adding to optimized block
                }
            }

            if (written_var != null)
            {
                live_set.remove(written_var); // remove destination from live set
            }

            live_set.addAll(read_vars); // add source vars to live set

            optimized_block.add(0, inst); // add kept instruction to front to maintain order
        }

        return optimized_block;
    }

    private static String get_written_var(IRInstruction inst)
    {
        if (inst.operands == null || inst.operands.length == 0) return null;

        switch (inst.opCode)
        {
            case ASSIGN:
                if (inst.operands.length == 2 && inst.operands[0] instanceof IRVariableOperand)
                {
                    return ((IRVariableOperand) inst.operands[0]).getName(); // return dest var
                }
                return null;
            case ADD: case SUB: case MULT: case DIV: case AND: case OR:
            case ARRAY_LOAD:
            case CALLR:
                if (inst.operands[0] instanceof IRVariableOperand)
                {
                    return ((IRVariableOperand) inst.operands[0]).getName(); // return dest var for math and loads
                }
                return null;
            default:
                return null;
        }
    }

    private static List<String> get_read_vars(IRInstruction inst)
    {
        List<String> reads = new ArrayList<>();
        if (inst.operands == null) return reads;

        int start_idx = 0;

        if (get_written_var(inst) != null)
        {
            start_idx = 1; // skip dest var if there is one
        }

        if (inst.opCode == IRInstruction.OpCode.ARRAY_STORE)
        {
            start_idx = 0; // array store reads everything
        }

        if (inst.opCode == IRInstruction.OpCode.CALLR)
        {
            start_idx = 2; // skip return var and function name
        } 
        else if (inst.opCode == IRInstruction.OpCode.CALL)
        {
            start_idx = 1; // skip function name
        }

        for (int i = start_idx; i < inst.operands.length; i++)
        {
            if (inst.operands[i] instanceof IRVariableOperand)
            {
                reads.add(((IRVariableOperand) inst.operands[i]).getName()); // add to reads list
            }
        }
        
        return reads;
    }
}