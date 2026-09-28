package mindustry.campaign.shared.io;

import arc.struct.*;
import mindustry.campaign.shared.SharedCampaignState;

import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * Allocation-conscious deep copier for the engine-independent Shared Campaign state graph.
 *
 * <p>The durable codec remains the authority for persistence and compatibility. It is intentionally <em>not</em>
 * used for ordinary in-memory copies: a codec round-trip duplicates every String through UTF-8 bytes and allocates
 * a full serialized buffer, which is especially expensive as the shared campaign state grows.
 * This copier creates fresh mutable containers/records while safely sharing immutable values such as Strings,
 * boxed primitives and enums.</p>
 */
public final class SharedCampaignStateCopy{
    private static final Set<Class<?>> immutable = Set.of(
        String.class, Boolean.class, Byte.class, Short.class, Integer.class, Long.class,
        Float.class, Double.class, Character.class
    );
    private static final ConcurrentMap<Class<?>, Field[]> fields = new ConcurrentHashMap<>();

    private SharedCampaignStateCopy(){}

    public static SharedCampaignState copy(SharedCampaignState state){
        return (SharedCampaignState)copyValue(Objects.requireNonNull(state, "state"));
    }

    public static SharedCampaignState.SectorState copySector(SharedCampaignState.SectorState sector){
        return (SharedCampaignState.SectorState)copyValue(Objects.requireNonNull(sector, "sector"));
    }

    public static SharedCampaignState.ActionState copyAction(SharedCampaignState.ActionState action){
        return (SharedCampaignState.ActionState)copyValue(Objects.requireNonNull(action, "action"));
    }

    /**
     * Fails closed when a persisted Shared Campaign state class gains a direct field whose raw type cannot be copied.
     * Tests call this as a schema gate so a future field addition cannot silently disappear from structural snapshots.
     */
    public static void validateSchema(){
        validateStateType(SharedCampaignState.class);
        for(Class<?> nested : SharedCampaignState.class.getDeclaredClasses()){
            if(!nested.isEnum() && !nested.isInterface() && !Modifier.isAbstract(nested.getModifiers())) validateStateType(nested);
        }
    }

    private static void validateStateType(Class<?> type){
        for(Field field : stateFields(type)){
            if(!supportedFieldType(field.getType())){
                throw new IllegalStateException("Unsupported Shared Campaign copy schema field " + type.getName() + "." + field.getName()
                    + " of type " + field.getType().getName());
            }
        }
    }

    private static boolean supportedFieldType(Class<?> type){
        if(type.isPrimitive() || type.isEnum() || immutable.contains(type) || type == byte[].class) return true;
        if(type.isArray()) return type.getComponentType().isPrimitive() || supportedFieldType(type.getComponentType());
        if(ObjectMap.class.isAssignableFrom(type) || ObjectSet.class.isAssignableFrom(type) || Seq.class.isAssignableFrom(type)) return true;
        return type == SharedCampaignState.class || type.getEnclosingClass() == SharedCampaignState.class;
    }

    /**
     * Returns an independent strategic snapshot suitable for UI/commit observers.
     * The clean schema keeps large world/model payloads outside SharedCampaignState, so a strategic snapshot is
     * currently identical to a full structural copy. Keeping this entry point preserves the separation for future
     * bounded client views without reintroducing product-specific model fields.
     */
    public static SharedCampaignState strategicCopy(SharedCampaignState state){
        return copy(state);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Object copyValue(Object value){
        if(value == null) return null;
        Class<?> type = value.getClass();
        if(type.isEnum() || immutable.contains(type)) return value;
        if(type == byte[].class) return ((byte[])value).clone();
        if(type.isArray()){
            int length = Array.getLength(value);
            Object copy = Array.newInstance(type.getComponentType(), length);
            if(type.getComponentType().isPrimitive()){
                System.arraycopy(value, 0, copy, 0, length);
            }else{
                for(int i = 0; i < length; i++) Array.set(copy, i, copyValue(Array.get(value, i)));
            }
            return copy;
        }
        if(value instanceof ObjectMap map){
            ObjectMap copy = new ObjectMap(Math.max(1, map.size));
            // Arc ObjectMap#iterator() reuses two iterator instances stored on the map. Concurrent snapshot copies of
            // the same authoritative state can therefore invalidate each other's iterator and throw
            // "#iterator() cannot be used nested." Use a dedicated iterator owned by this copy operation instead.
            ObjectMap.Entries entries = new ObjectMap.Entries(map);
            while(entries.hasNext){
                ObjectMap.Entry entry = (ObjectMap.Entry)entries.next();
                copy.put(copyValue(entry.key), copyValue(entry.value));
            }
            return copy;
        }
        if(value instanceof ObjectSet set){
            ObjectSet copy = new ObjectSet(Math.max(1, set.size));
            // ObjectSet has the same reusable-iterator contract as ObjectMap. A private iterator per copy keeps
            // strategic/UI snapshots safe when multiple readers copy the same state concurrently.
            ObjectSet.ObjectSetIterator iterator = new ObjectSet.ObjectSetIterator(set);
            while(iterator.hasNext) copy.add(copyValue(iterator.next()));
            return copy;
        }
        if(value instanceof Seq seq){
            Seq copy = new Seq(seq.ordered, Math.max(1, seq.size));
            // Avoid Seq's shared iterable state as well; indexed reads are stable for an owner-frozen snapshot.
            for(int i = 0; i < seq.size; i++) copy.add(copyValue(seq.get(i)));
            return copy;
        }
        if(type == SharedCampaignState.class || type.getEnclosingClass() == SharedCampaignState.class){
            Object copy = instantiate(type);
            for(Field field : stateFields(type)){
                try{
                    field.set(copy, copyValue(field.get(value)));
                }catch(IllegalAccessException error){
                    throw new IllegalStateException("Cannot copy Shared Campaign field " + type.getName() + "." + field.getName(), error);
                }
            }
            return copy;
        }
        throw new IllegalArgumentException("Unsupported mutable Shared Campaign state value: " + type.getName());
    }

    private static Object instantiate(Class<?> type){
        try{
            Constructor<?> constructor = type.getDeclaredConstructor();
            // setAccessible(boolean) is part of the API-1 Android reflection surface. Using it directly avoids
            // hard references to Java 9 canAccess/trySetAccessible while retaining the same fail-closed contract.
            constructor.setAccessible(true);
            return constructor.newInstance();
        }catch(ReflectiveOperationException | SecurityException error){
            throw new IllegalStateException("Shared Campaign state type has no usable no-arg constructor: " + type.getName(), error);
        }
    }

    private static Field[] stateFields(Class<?> type){
        return fields.computeIfAbsent(type, ignored -> Arrays.stream(type.getDeclaredFields())
            .filter(field -> !Modifier.isStatic(field.getModifiers()) && !field.isSynthetic())
            .peek(field -> {
                try{
                    field.setAccessible(true);
                }catch(SecurityException denied){
                    throw new IllegalStateException("Cannot access Shared Campaign state field " + type.getName() + "." + field.getName(), denied);
                }
            })
            .sorted(Comparator.comparing(Field::getName))
            .toArray(Field[]::new));
    }
}
