package mindustry.runtime;

import mindustry.game.*;
import org.junit.jupiter.api.*;

import java.lang.reflect.*;

import static org.junit.jupiter.api.Assertions.*;

@Tag("shared-campaign-parallel")
class FogConcurrencyOwnershipTests{
    @Test
    void fogDoesNotAllocateDedicatedThreadClassesPerGameContext(){
        for(Class<?> nested : FogControl.class.getDeclaredClasses()){
            assertFalse(Thread.class.isAssignableFrom(nested), "fog work must use the process-bounded runtime worker infrastructure, not one Thread subclass per context: " + nested.getName());
        }
        for(Field field : FogControl.class.getDeclaredFields()){
            assertFalse(Thread.class.isAssignableFrom(field.getType()), "FogControl must not retain dedicated Thread fields: " + field.getName());
        }
    }
}
