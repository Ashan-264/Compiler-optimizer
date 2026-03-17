import ir.*;
import ir.operand.*;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.util.*;

public class TigerOptimizer
{
    // struct-like class to hold basic block data and cfg edges
    static class BasicBlock
    {
        List<IRInstruction> instructions = new ArrayList<>();
        List<BasicBlock> predecessors = new ArrayList<>(); // cfg edges in
        List<BasicBlock> successors = new ArrayList<>();   // cfg edges out
        
        Set<IRInstruction> gen = new HashSet<>();
        Set<IRInstruction> kill = new HashSet<>();
        Set<IRInstruction> in = new HashSet<>();
        Set<IRInstruction> out = new HashSet<>();
    }

    public static void main(String[] args)
    {
        if (args.length != 2)
        {
            System.err.println("Usage: java TigerOptimizer <input_ir> <output_ir>"); // error if wrong args
            System.exit(1);
        }

        String input_file = args[0];  // get input file path
        String output_file = args[1]; // get output file path

        try
        {
            IRReader reader = new IRReader(); // intialize reader
            IRProgram program = reader.parseIRFile(input_file); // parse the ir file into program struct

            for (IRFunction function : program.functions) //
            {
                optimize_global(function); // run reaching definitions on each function
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

    private static void optimize_global(IRFunction function)
    {
        // 1. map all variable definitions in the function to build kill sets later
        Map<String, Set<IRInstruction>> all_defs = new HashMap<>();
        for (IRInstruction inst : function.instructions)
        {
            String written_var = get_written_var(inst);
            if (written_var != null)
            {
                all_defs.computeIfAbsent(written_var, k -> new HashSet<>()).add(inst); // store def
            }
        }

        // 2. partition instructions into basic blocks
        List<BasicBlock> blocks = new ArrayList<>();
        BasicBlock current_block = new BasicBlock();

        for (IRInstruction inst : function.instructions)
        {
            if (inst.opCode == IRInstruction.OpCode.LABEL && !current_block.instructions.isEmpty())
            { // if label, close block and start new
                blocks.add(current_block);
                current_block = new BasicBlock();
            }

            current_block.instructions.add(inst); // add instruction to block

            String op_name = inst.opCode.toString();
            if (op_name.startsWith("br") || op_name.equals("goto") || op_name.equals("return"))
            { // if branch or return, close block
                blocks.add(current_block);
                current_block = new BasicBlock();
            }
        }
        if (!current_block.instructions.isEmpty())
        {
            blocks.add(current_block); // catch last block
        }

        // 3. wire the control flow graph edges
        Map<String, BasicBlock> label_map = new HashMap<>();
        for (BasicBlock b : blocks)
        {
            IRInstruction first_inst = b.instructions.get(0);
            if (first_inst.opCode == IRInstruction.OpCode.LABEL)
            {
                String label_name = ((IRLabelOperand) first_inst.operands[0]).getName(); //
                label_map.put(label_name, b); // map label string to block ptr
            }
        }

        for (int i = 0; i < blocks.size(); i++)
        {
            BasicBlock b = blocks.get(i);
            IRInstruction last_inst = b.instructions.get(b.instructions.size() - 1);
            String op = last_inst.opCode.toString();

            if (op.equals("goto"))
            {
                String target = ((IRLabelOperand) last_inst.operands[0]).getName(); //
                BasicBlock succ = label_map.get(target);
                if (succ != null)
                {
                    b.successors.add(succ); // connect jump target
                    succ.predecessors.add(b);
                }
            }
            else if (op.startsWith("br"))
            {
                String target = ((IRLabelOperand) last_inst.operands[0]).getName(); //
                BasicBlock succ1 = label_map.get(target);
                if (succ1 != null)
                {
                    b.successors.add(succ1); // taken branch
                    succ1.predecessors.add(b);
                }
                if (i + 1 < blocks.size())
                {
                    BasicBlock succ2 = blocks.get(i + 1);
                    b.successors.add(succ2); // fallthrough branch
                    succ2.predecessors.add(b);
                }
            }
            else if (!op.equals("return"))
            {
                if (i + 1 < blocks.size())
                {
                    BasicBlock succ = blocks.get(i + 1);
                    b.successors.add(succ); // normal fallthrough
                    succ.predecessors.add(b);
                }
            }
        }

        // 4. calculate gen and kill sets for each block
        for (BasicBlock b : blocks)
        {
            for (IRInstruction inst : b.instructions)
            {
                String v = get_written_var(inst);
                if (v != null)
                {
                    b.gen.removeIf(d -> get_written_var(d).equals(v)); // kill prior defs in same block
                    b.gen.add(inst); // add new def
                    
                    Set<IRInstruction> other_defs = new HashSet<>(all_defs.get(v));
                    other_defs.remove(inst);
                    b.kill.addAll(other_defs); // kill all other defs in program
                }
            }
        }

        // 5. iterative data flow solver for in and out sets
        boolean changed = true;
        while (changed)
        {
            changed = false;
            for (BasicBlock b : blocks)
            {
                Set<IRInstruction> old_out = new HashSet<>(b.out);
                
                b.in.clear();
                for (BasicBlock p : b.predecessors)
                {
                    b.in.addAll(p.out); // IN = union of predecessor OUTs
                }

                b.out.clear();
                b.out.addAll(b.in);
                b.out.removeAll(b.kill);
                b.out.addAll(b.gen); // OUT = GEN U (IN - KILL)

                if (!b.out.equals(old_out))
                {
                    changed = true; // continue loop if out set changed
                }
            }
        }

        // 6. map exact reaching definitions to every instruction
        Map<IRInstruction, Set<IRInstruction>> reaching_at_inst = new HashMap<>();
        for (BasicBlock b : blocks)
        {
            Set<IRInstruction> current_reaching = new HashSet<>(b.in);
            for (IRInstruction inst : b.instructions)
            {
                reaching_at_inst.put(inst, new HashSet<>(current_reaching)); // snapshot reaching defs
                
                String v = get_written_var(inst);
                if (v != null)
                {
                    current_reaching.removeIf(d -> get_written_var(d).equals(v)); // overwrite old def
                    current_reaching.add(inst); // add new def
                }
            }
        }

        // 7. mark and sweep (dead code elimination)
        Set<IRInstruction> marked = new HashSet<>();
        Queue<IRInstruction> work_list = new LinkedList<>();

        // initial mark pass
        for (IRInstruction inst : function.instructions)
        {
            String op = inst.opCode.toString();
            // keep I/O, returns, arrays, and control flow (without branch removal)
            if (op.equals("callr") || op.equals("call") || op.equals("array_store") || 
                op.equals("return") || op.equals("label") || op.equals("goto") || op.startsWith("br"))
            {
                marked.add(inst);
                work_list.add(inst); // push to queue
            }
        }

        // trace dependencies
        while (!work_list.isEmpty())
        {
            IRInstruction inst = work_list.poll();
            List<String> reads = get_read_vars(inst);
            Set<IRInstruction> reach = reaching_at_inst.get(inst); // get defs that reach this read

            for (IRInstruction r : reach)
            {
                String defined_var = get_written_var(r);
                if (reads.contains(defined_var) && !marked.contains(r))
                {
                    marked.add(r); // mark useful
                    work_list.add(r); // push to queue to trace its dependencies
                }
            }
        }

        // sweep phase
        List<IRInstruction> final_instrs = new ArrayList<>();
        for (BasicBlock b : blocks)
        {
            for (IRInstruction inst : b.instructions)
            {
                if (marked.contains(inst))
                {
                    final_instrs.add(inst); // keep marked instructions
                }
            }
        }
        
        function.instructions = final_instrs; // replace old instructions with swept list
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