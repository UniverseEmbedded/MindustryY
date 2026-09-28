package mindustry.io;

import arc.graphics.*;
import arc.math.geom.*;
import arc.util.*;
import arc.util.serialization.*;
import arc.util.serialization.Json.*;
import mindustry.*;
import mindustry.audio.*;
import mindustry.content.*;
import mindustry.ctype.*;
import mindustry.game.*;
import mindustry.game.MapObjectives.*;
import mindustry.maps.*;
import mindustry.type.*;
import mindustry.world.*;
import mindustry.world.meta.*;

import java.io.*;

@SuppressWarnings("unchecked")
public class JsonIO{
    /**
     * Ordered class-tag registrations replayed into each thread-owned Arc Json codec.
     * Arc Json keeps a reverse class->tag mapping, so registration order is semantic: compatibility aliases are
     * registered first and canonical tags last. A hash map loses that order and can make serializers emit a legacy
     * lowercase alias; MapObjectives deliberately rejects those legacy aliases on read, which previously erased
     * campaign objectives during network Rules round-trips.
     */
    private static final java.util.concurrent.CopyOnWriteArrayList<ClassTagRegistration> dynamicClassTags = new java.util.concurrent.CopyOnWriteArrayList<>();
    private static final java.util.concurrent.atomic.AtomicLong configurationGeneration = new java.util.concurrent.atomic.AtomicLong();

    /**
     * Compatibility instance for external callers that still retain the historical field. MindustryY runtime code
     * must use {@link #current()} or the static helper methods so concurrent GameContexts never share Arc Json's
     * mutable writer/current-item state.
     */
    @Deprecated
    public static final Json json = createJson();
    private static final ThreadLocal<JsonHolder> localJson;

    private record JsonHolder(Json value, long generation){}
    private record ClassTagRegistration(String tag, Class<?> type){}

    private static Json createJson(){
        return new Json(){
        @Override
        public void writeValue(Object value, Class knownType, Class elementType){
            if(value instanceof MappableContent c){
                try{
                    getWriter().value(c.name);
                }catch(IOException e){
                    throw new RuntimeException(e);
                }
            }else{
                super.writeValue(value, knownType, elementType);
            }
        }

        @Override
        protected String convertToString(Object object){
            if(object instanceof MappableContent c) return c.name;
            return super.convertToString(object);
        }
        };
    }

    /** Returns a Json codec owned by the current thread. Arc Json mutates writer/parser state and is not thread-safe. */
    public static Json current(){
        JsonHolder holder = localJson.get();
        long generation = configurationGeneration.get();
        if(holder.generation != generation){
            Json value = configuredJson();
            holder = new JsonHolder(value, generation);
            localJson.set(holder);
        }
        return holder.value;
    }

    private static Json configuredJson(){
        Json value = createJson();
        configure(value);
        for(ClassTagRegistration registration : dynamicClassTags){
            value.addClassTag(registration.tag, registration.type);
        }
        return value;
    }

    public static void writeBytes(Object value, Class<?> elementType, DataOutputStream output){
        Json json = current();
        json.setWriter(new UBJsonWriter(output));
        json.writeValue(value, value == null ? null : value.getClass(), elementType);
    }

    public static <T> T readBytes(Class<T> type, Class<?> elementType, DataInputStream input) throws IOException{
        return current().readValue(type, elementType, new UBJsonReader().parseWihoutClosing(input));
    }

    public static String write(Object object){
        return current().toJson(object, object.getClass());
    }

    public static <T> T copy(T object, T dest){
        current().copyFields(object, dest);
        return dest;
    }

    public static <T> T copy(T object){
        return read((Class<T>)object.getClass(), write(object));
    }

    public static <T> T read(Class<T> type, String string){
        return current().fromJson(type, string.replace("io.anuke.", ""));
    }

    public static <T> T read(T base, String string){
        current().readFields(base, new JsonReader().parse(string.replace("io.anuke.", "")));
        return base;
    }

    public static String print(String in){
        return current().prettyPrint(in);
    }

    public static void classTag(String tag, Class<?> type){
        dynamicClassTags.add(new ClassTagRegistration(tag, type));
        synchronized(json){ json.addClassTag(tag, type); }
        configurationGeneration.incrementAndGet();
    }

    static{
        configure(json);
        localJson = ThreadLocal.withInitial(() -> new JsonHolder(configuredJson(), configurationGeneration.get()));
    }

    private static void configure(Json json){
        json.setElementType(Rules.class, "spawns", SpawnGroup.class);
        json.setElementType(Rules.class, "loadout", ItemStack.class);

        json.setSerializer(MusicContainer.class, new Serializer<>(){
            @Override
            public void write(Json json, MusicContainer object, Class knownType){
                json.writeValue(object.name);
            }

            @Override
            public MusicContainer read(Json json, JsonValue jsonData, Class type){
                return new MusicContainer(jsonData.isString() ? jsonData.asString() : "");
            }
        });

        json.setSerializer(Color.class, new Serializer<>(){
            @Override
            public void write(Json json, Color object, Class knownType){
                json.writeValue(object.toString());
            }

            @Override
            public Color read(Json json, JsonValue jsonData, Class type){
                if(jsonData.isString()){
                    return Color.valueOf(jsonData.asString());
                }
                Color out = new Color();
                json.readFields(out, jsonData);
                return out;
            }
        });

        json.setSerializer(Sector.class, new Serializer<>(){
            @Override
            public void write(Json json, Sector object, Class knownType){
                json.writeValue(object.planet.name + "-" + object.id);
            }

            @Override
            public Sector read(Json json, JsonValue jsonData, Class type){
                String name = jsonData.asString();
                int idx = name.lastIndexOf('-');
                return Vars.content.<Planet>getByName(ContentType.planet, name.substring(0, idx)).sectors.get(Integer.parseInt(name.substring(idx + 1)));
            }
        });

        json.setSerializer(SectorPreset.class, new Serializer<>(){
            @Override
            public void write(Json json, SectorPreset object, Class knownType){
                json.writeValue(object.name);
            }

            @Override
            public SectorPreset read(Json json, JsonValue jsonData, Class type){
                return Vars.content.getByName(ContentType.sector, jsonData.asString());
            }
        });

        json.setSerializer(Liquid.class, new Serializer<>(){
            @Override
            public void write(Json json, Liquid object, Class knownType){
                json.writeValue(object.name);
            }

            @Override
            public Liquid read(Json json, JsonValue jsonData, Class type){
                if(jsonData.asString() == null) return Liquids.water;
                Liquid i = Vars.content.getByName(ContentType.liquid, jsonData.asString());
                return i == null ? Liquids.water : i;
            }
        });

        json.setSerializer(Attribute.class, new Serializer<>(){
            @Override
            public void write(Json json, Attribute object, Class knownType){
                json.writeValue(object.name);
            }

            @Override
            public Attribute read(Json json, JsonValue jsonData, Class type){
                return Attribute.get(jsonData.asString());
            }
        });

        json.setSerializer(Item.class, new Serializer<>(){
            @Override
            public void write(Json json, Item object, Class knownType){
                json.writeValue(object.name);
            }

            @Override
            public Item read(Json json, JsonValue jsonData, Class type){
                if(jsonData.asString() == null) return Items.copper;
                Item i = Vars.content.getByName(ContentType.item, jsonData.asString());
                return i == null ? Items.copper : i;
            }
        });

        json.setSerializer(Team.class, new Serializer<>(){
            @Override
            public void write(Json json, Team object, Class knownType){
                json.writeValue(object.id);
            }

            @Override
            public Team read(Json json, JsonValue jsonData, Class type){
                return Team.get(jsonData.asInt());
            }
        });

        json.setSerializer(Block.class, new Serializer<>(){
            @Override
            public void write(Json json, Block object, Class knownType){
                json.writeValue(object.name);
            }

            @Override
            public Block read(Json json, JsonValue jsonData, Class type){
                Block block = Vars.content.getByName(ContentType.block, jsonData.asString());
                if(block == null) block = Vars.content.getByName(ContentType.block, SaveVersion.fallback.get(jsonData.asString(), ""));
                return block == null ? Blocks.air : block;
            }
        });

        json.setSerializer(Planet.class, new Serializer<>(){
            @Override
            public void write(Json json, Planet object, Class knownType){
                json.writeValue(object.name);
            }

            @Override
            public Planet read(Json json, JsonValue jsonData, Class type){
                if(jsonData.asString() == null){
                    return null;
                }
                Planet block = Vars.content.getByName(ContentType.planet, jsonData.asString());
                return block == null ? Planets.serpulo : block;
            }
        });

        json.setSerializer(Weather.class, new Serializer<>(){
            @Override
            public void write(Json json, Weather object, Class knownType){
                json.writeValue(object.name);
            }

            @Override
            public Weather read(Json json, JsonValue jsonData, Class type){
                return Vars.content.getByName(ContentType.weather, jsonData.asString());
            }
        });

        json.setSerializer(UnitType.class, new Serializer<>(){
            @Override
            public void write(Json json, UnitType object, Class knownType){
                json.writeValue(object.name);
            }

            @Override
            public UnitType read(Json json, JsonValue jsonData, Class type){
                if(jsonData.asString() == null) return UnitTypes.dagger;
                UnitType u = Vars.content.getByName(ContentType.unit, jsonData.asString());
                return u == null ? UnitTypes.dagger : u;
            }
        });

        json.setSerializer(ItemStack.class, new Serializer<>(){
            @Override
            public void write(Json json, ItemStack object, Class knownType){
                json.writeObjectStart();
                json.writeValue("item", object.item);
                json.writeValue("amount", object.amount);
                json.writeObjectEnd();
            }

            @Override
            public ItemStack read(Json json, JsonValue jsonData, Class type){
                return new ItemStack(json.getSerializer(Item.class).read(json, jsonData.get("item"), Item.class), jsonData.getInt("amount"));
            }
        });

        json.setSerializer(UnlockableContent.class, new Serializer<>(){
            @Override
            public void write(Json json, UnlockableContent object, Class knownType){
                json.writeValue(object == null ? null : object.name);
            }

            @Override
            public UnlockableContent read(Json json, JsonValue jsonData, Class type){
                if(jsonData.isNull()) return null;
                String str = jsonData.asString();
                var map = Vars.content.byName(str);
                return map instanceof UnlockableContent u ? u : null;
            }
        });

        json.setSerializer(MapObjectives.class, new Serializer<>(){
            @Override
            public void write(Json json, MapObjectives exec, Class knownType){
                json.writeArrayStart();
                for(var obj : exec){
                    json.writeObjectStart(obj.getClass().isAnonymousClass() ? obj.getClass().getSuperclass() : obj.getClass(), null);
                    json.writeFields(obj);

                    json.writeArrayStart("parents");
                    for(var parent : obj.parents){
                        json.writeValue(exec.all.indexOf(parent));
                    }

                    json.writeArrayEnd();

                    json.writeValue("editorPos", Point2.pack(obj.editorX, obj.editorY));
                    json.writeObjectEnd();
                }

                json.writeArrayEnd();
            }

            @Override
            public MapObjectives read(Json json, JsonValue data, Class type){
                var exec = new MapObjectives();
                // First iteration to instantiate the objectives.
                for(var value = data.child; value != null; value = value.next){
                    //glenn why did you implement this in the least backwards compatible way possible
                    //the old objectives had lowercase class tags, now they're uppercase and either way I can't deserialize them without errors
                    if(value.has("class") && Character.isLowerCase(value.getString("class").charAt(0))){
                        return new MapObjectives();
                    }

                    MapObjective obj = json.readValue(MapObjective.class, value);

                    if(value.has("editorPos")){
                        int pos = value.getInt("editorPos");
                        obj.editorX = Point2.x(pos);
                        obj.editorY = Point2.y(pos);
                    }

                    exec.all.add(obj);
                    obj.validate();
                }

                // Second iteration to map the parents.
                int i = 0;
                for(var value = data.child; value != null; value = value.next, i++){
                    for(var parent = value.get("parents").child; parent != null; parent = parent.next){
                        int val = parent.asInt();
                        if(val >= 0 && val < exec.all.size){
                            exec.all.get(i).parents.add(exec.all.get(val));
                        }
                    }
                }

                return exec;
            }
        });

        //use short names for all filter types
        for(var filter : Maps.allFilterTypes){
            var i = filter.get();
            json.addClassTag(Strings.camelize(i.getClass().getSimpleName().replace("Filter", "")), i.getClass());
        }
    }
}