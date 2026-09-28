package mindustry.world.modules;

import arc.math.*;
import arc.struct.*;
import arc.util.*;
import arc.util.io.*;
import mindustry.type.*;
import mindustry.runtime.*;

import java.util.*;

import static mindustry.Vars.*;

public class ItemModule extends BlockModule{
    /**
     * Legacy API compatibility only. Authoritative runtime code must use {@link #emptyForContext()} so one live
     * GameContext can never observe mutations made through another world's no-core fallback module.
     */
    @Deprecated
    public static final ItemModule empty = new ImmutableEmptyItemModule();
    private static final Object contextEmptyKey = new Object();

    /** Read-only compatibility sentinel. Legacy callers may inspect it, but writes are intentionally discarded. */
    private static final class ImmutableEmptyItemModule extends ItemModule{
        @Override public void set(ItemModule other){}
        @Override public void set(Item item, int amount){}
        @Override public void add(Iterable<ItemStack> stacks){}
        @Override public void add(ItemSeq stacks){}
        @Override public void add(ItemModule items){}
        @Override public void add(Item item, int amount){}
        @Override public void handleFlow(Item item, int amount){}
        @Override public void undoFlow(Item item){}
        @Override public void remove(Item item, int amount){}
        @Override public void remove(ItemStack[] stacks){}
        @Override public void remove(ItemSeq stacks){}
        @Override public void remove(Iterable<ItemStack> stacks){}
        @Override public void remove(ItemStack stack){}
        @Override public void clear(){}
        @Override public void checkArrayCapacity(int size){}
        @Override public void read(Reads read, boolean legacy){
            int count = legacy ? read.ub() : read.s();
            for(int i = 0; i < count; i++){
                if(legacy) read.ub(); else read.s();
                read.i();
            }
        }
    }

    /** Runtime-local mutable empty module used when a team has no core in the current world. */
    public static ItemModule emptyForContext(){
        return RuntimeContexts.requireCurrent().localState(contextEmptyKey, ItemModule::new);
    }

    /** Total number of samples of flow rate that are taken. */
    public static int flowWindowSize = 12;
    /** Interval between which samples are added to the window, in ticks. */
    public static float flowPollInterval = 10f;
    /** Visual refresh rate of the value, in ticks. Doesn't affect values, just reduces high-frequency flickering. */
    public static float flowVisualRefreshInterval = 15f;

    private float[] flowSums;
    private float[] displayFlow;
    private final Bits flowBits = new Bits();
    private final Interval flowTimer = new Interval(2);

    protected int[] items = new int[content.items().size];
    protected int total;
    protected int takeRotation;

    private @Nullable WindowedMean[] flow;

    public ItemModule copy(){
        ItemModule out = new ItemModule();
        out.set(this);
        return out;
    }

    public void set(ItemModule other){
        total = other.total;
        takeRotation = other.takeRotation;
        System.arraycopy(other.items, 0, items, 0, items.length);
    }

    public void updateFlow(){
        //update the flow at N fps at most
        if(flowTimer.get(1, flowPollInterval)){
            int len = content.items().size;
            if(items.length != len) items = Arrays.copyOf(items, len);

            if(flow == null || flow.length != len || flowSums == null || displayFlow == null){
                flow = new WindowedMean[len];
                for(int i = 0; i < len; i++){
                    flow[i] = new WindowedMean(flowWindowSize);
                }
                flowSums = new float[len];
                displayFlow = new float[len];
                flowBits.clear();
                Arrays.fill(displayFlow, -1);
            }

            boolean updateFlow = flowTimer.get(flowVisualRefreshInterval);

            for(int i = 0; i < items.length; i++){
                flow[i].add(flowSums[i]);
                if(flowSums[i] > 0){
                    flowBits.set(i);
                }
                flowSums[i] = 0;

                if(updateFlow){
                    displayFlow[i] = flow[i].hasEnoughData() ? flow[i].mean() / flowPollInterval : -1;
                }
            }
        }
    }

    public void stopFlow(){
        flow = null;
    }

    public int length(){
        return items.length;
    }

    /** @return a specific item's flow rate in items/s; any value < 0 means not ready.*/
    public float getFlowRate(Item item){
        return flow == null ? -1f : displayFlow[item.id] * 60;
    }

    public boolean hasFlowItem(Item item){
        return flow != null && flowBits.get(item.id);
    }

    public void each(ItemConsumer cons){
        for(int i = 0; i < items.length; i++){
            if(items[i] != 0){
                cons.accept(content.item(i), items[i]);
            }
        }
    }

    public float sum(ItemCalculator calc){
        float sum = 0f;
        for(int i = 0; i < items.length; i++){
            if(items[i] > 0){
                sum += calc.get(content.item(i), items[i]);
            }
        }
        return sum;
    }

    public boolean has(int id){
        return items[id] > 0;
    }

    public boolean has(Item item){
        return get(item) > 0;
    }

    public boolean has(Item item, int amount){
        return get(item) >= amount;
    }

    public boolean has(ItemStack[] stacks){
        for(ItemStack stack : stacks){
            if(!has(stack.item, stack.amount)) return false;
        }
        return true;
    }

    public boolean has(ItemSeq items){
        for(Item item : content.items()){
            if(!has(item, items.get(item))){
                return false;
            }
        }
        return true;
    }

    public boolean has(Iterable<ItemStack> stacks){
        for(ItemStack stack : stacks){
            if(!has(stack.item, stack.amount)) return false;
        }
        return true;
    }

    public boolean has(ItemStack[] stacks, float multiplier){
        for(ItemStack stack : stacks){
            if(stack.item.id >= items.length || !has(stack.item, Math.round(stack.amount * multiplier))) return false;
        }
        return true;
    }

    /**
     * Returns true if this entity has at least one of each item in each stack.
     */
    public boolean hasOne(ItemStack[] stacks){
        for(ItemStack stack : stacks){
            if(!has(stack.item, 1)) return false;
        }
        return true;
    }

    public boolean empty(){
        return total == 0;
    }

    public int total(){
        return total;
    }

    public boolean any(){
        return total > 0;
    }

    public @Nullable Item first(){
        for(int i = 0; i < items.length; i++){
            if(items[i] > 0){
                return content.item(i);
            }
        }
        return null;
    }

    public @Nullable Item take(){
        for(int i = 0; i < items.length; i++){
            int index = (i + takeRotation);
            if(index >= items.length) index -= items.length;
            if(items[index] > 0){
                items[index] --;
                total --;
                takeRotation = index + 1;
                return content.item(index);
            }
        }
        return null;
    }

    public int get(int id){
        return items[id];
    }

    public int get(Item item){
        return items[item.id];
    }

    public void set(Item item, int amount){
        total += (amount - items[item.id]);
        items[item.id] = amount;
    }

    public void add(Iterable<ItemStack> stacks){
        for(ItemStack stack : stacks){
            add(stack.item, stack.amount);
        }
    }

    public void add(ItemSeq stacks){
        stacks.each(this::add);
    }

    public void add(ItemModule items){
        for(int i = 0; i < items.items.length; i++){
            add(i, items.items[i]);
        }
    }

    public void add(Item item, int amount){
        add(item.id, amount);
    }

    private void add(int item, int amount){
        items[item] += amount;
        total += amount;
        if(flow != null){
            flowSums[item] += amount;
        }
    }

    public void handleFlow(Item item, int amount){
        if(flow != null){
            flowSums[item.id] += amount;
        }
    }

    public void undoFlow(Item item){
        if(flow != null){
            flowSums[item.id] -= 1;
        }
    }

    public void remove(Item item, int amount){
        amount = Math.min(amount, items[item.id]);

        items[item.id] -= amount;
        total -= amount;
    }

    public void remove(ItemStack[] stacks){
        for(ItemStack stack : stacks) remove(stack.item, stack.amount);
    }

    public void remove(ItemSeq stacks){
        stacks.each(this::remove);
    }

    public void remove(Iterable<ItemStack> stacks){
        for(ItemStack stack : stacks) remove(stack.item, stack.amount);
    }

    public void remove(ItemStack stack){
        remove(stack.item, stack.amount);
    }

    public void clear(){
        Arrays.fill(items, 0);
        total = 0;
    }

    public void checkArrayCapacity(int size){
        if(items.length != size) items = Arrays.copyOf(items, size);
        flowSums = null;
        displayFlow = null;
        flowBits.clear();
        flow = null;
    }

    @Override
    public void write(Writes write){
        int amount = 0;
        for(int item : items){
            if(item > 0) amount++;
        }

        write.s(amount);

        for(int i = 0; i < items.length; i++){
            if(items[i] > 0){
                write.s(i); //item ID
                write.i(items[i]); //item amount
            }
        }
    }

    @Override
    public void read(Reads read, boolean legacy){
        //just in case, reset items
        Arrays.fill(items, 0);
        int count = legacy ? read.ub() : read.s();
        total = 0;

        for(int j = 0; j < count; j++){
            int itemid = legacy ? read.ub() : read.s();
            int itemamount = read.i();
            Item item = content.item(itemid);
            if(item != null){
                items[item.id] = itemamount;
                total += itemamount;
            }
        }
    }

    public interface ItemConsumer{
        void accept(Item item, int amount);
    }

    public interface ItemCalculator{
        float get(Item item, int amount);
    }

    @Override
    public String toString(){
        var res = new StringBuilder();
        res.append("ItemModule{");
        boolean any = false;
        for(int i = 0; i < items.length; i++){
            if(items[i] != 0){
                res.append(content.items().get(i).name).append(":").append(items[i]).append(",");
                any = true;
            }
        }
        if(any){
            res.setLength(res.length() - 1);
        }
        res.append("}");
        return res.toString();
    }
}
