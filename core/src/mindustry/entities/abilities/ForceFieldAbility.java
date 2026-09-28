package mindustry.entities.abilities;

import arc.*;
import arc.audio.*;
import arc.func.*;
import arc.graphics.*;
import arc.graphics.g2d.*;
import arc.math.*;
import arc.math.geom.*;
import arc.scene.ui.layout.*;
import arc.util.*;
import mindustry.*;
import mindustry.content.*;
import mindustry.gen.*;
import mindustry.graphics.*;
import mindustry.ui.*;

import static mindustry.Vars.*;

public class ForceFieldAbility extends Ability{
    /** Shield radius. */
    public float radius = 60f;
    /** Shield regen speed in damage/tick. */
    public float regen = 0.1f;
    /** Maximum shield. */
    public float max = 200f;
    /** Cooldown after the shield is broken, in ticks. */
    public float cooldown = 60f * 5;
    /** Sides of shield polygon. */
    public int sides = 6;
    /** Rotation of shield. */
    public float rotation = 0f;

    public Sound breakSound = Sounds.shieldBreakSmall;
    public Sound hitSound = Sounds.shieldHit;
    public float hitSoundVolume = 0.12f;

    /** State. */
    protected float radiusScale, alpha;
    protected boolean wasBroken = true;

    private final ThreadLocal<Scratch> scratch = ThreadLocal.withInitial(Scratch::new);

    private static class Scratch{
        float realRad; Unit unit; ForceFieldAbility field;
        final Cons<Bullet> shieldConsumer = b -> {
            if(unit != null && field != null && b.team != unit.team && b.type.absorbable && Intersector.isInRegularPolygon(field.sides, unit.x, unit.y, realRad, field.rotation, b.x(), b.y()) && unit.shield > 0){
                b.absorb(); Fx.absorb.at(b);
                field.hitSound.at(b.x, b.y, 1f + Mathf.range(0.1f), field.hitSoundVolume);
                unit.shield -= b.type().shieldDamage(b); field.alpha = 1f;
            }
        };
    }

    public ForceFieldAbility(float radius, float regen, float max, float cooldown){
        this.radius = radius;
        this.regen = regen;
        this.max = max;
        this.cooldown = cooldown;
    }

    public ForceFieldAbility(float radius, float regen, float max, float cooldown, int sides, float rotation){
        this.radius = radius;
        this.regen = regen;
        this.max = max;
        this.cooldown = cooldown;
        this.sides = sides;
        this.rotation = rotation;
    }

    ForceFieldAbility(){}

    public float scaledMax(Unit unit){
        return max * Vars.game().state.rules.unitHealth(unit.team);
    }

    @Override
    public void addStats(Table t){
        super.addStats(t);
        t.add(Core.bundle.format("bullet.range", Strings.autoFixed(radius / tilesize, 2)));
        t.row();
        t.add(abilityStat("shield", Strings.autoFixed(max, 2)));
        t.row();
        t.add(abilityStat("repairspeed", Strings.autoFixed(regen * 60f, 2)));
        t.row();
        t.add(abilityStat("cooldown", Strings.autoFixed(cooldown / 60f, 2)));
    }

    @Override
    public void update(Unit unit){
        if(unit.shield <= 0f && !wasBroken){
            unit.shield -= cooldown * regen;

            Fx.shieldBreak.at(unit.x, unit.y, radius, unit.type.shieldColor(unit), this);
            breakSound.at(unit.x, unit.y);
        }

        wasBroken = unit.shield <= 0f;

        if(unit.shield < scaledMax(unit)){
            unit.shield += Time.delta() * regen;
        }

        alpha = Math.max(alpha - Time.delta()/10f, 0f);

        if(unit.shield > 0){
            radiusScale = Mathf.lerpDelta(radiusScale, 1f, 0.06f);
            Scratch state = scratch.get();
            state.unit = unit; state.field = this;
            checkRadius(unit);
            try{
                Groups.current().bullet.intersect(unit.x - state.realRad, unit.y - state.realRad, state.realRad * 2f, state.realRad * 2f, state.shieldConsumer);
            }finally{ state.unit = null; state.field = null; }
        }else{
            radiusScale = 0f;
        }
    }

    @Override
    public void death(Unit unit){

        //self-destructing units can have a shield on death
        if(unit.shield > 0f && !wasBroken){
            Fx.shieldBreak.at(unit.x, unit.y, radius, unit.type.shieldColor(unit), sides);
            breakSound.at(unit.x, unit.y);
        }
    }

    @Override
    public void draw(Unit unit){
        Scratch state = scratch.get();
        checkRadius(unit);

        if(unit.shield > 0){
            Draw.color(unit.type.shieldColor(unit), Color.white, Mathf.clamp(alpha));

            if(Vars.renderer.animateShields){
                Draw.z(Layer.shields + 0.001f * alpha);
                Fill.poly(unit.x, unit.y, sides, state.realRad, rotation);
            }else{
                Draw.z(Layer.shields);
                Lines.stroke(1.5f);
                Draw.alpha(0.09f);
                Fill.poly(unit.x, unit.y, sides, radius, rotation);
                Draw.alpha(1f);
                Lines.poly(unit.x, unit.y, sides, radius, rotation);
            }
        }
    }

    @Override
    public void displayBars(Unit unit, Table bars){
        bars.add(new Bar("stat.shieldhealth", Pal.accent, () -> unit.shield / scaledMax(unit))).row();
    }

    @Override
    public void created(Unit unit){
        unit.shield = scaledMax(unit);
    }

    public void checkRadius(Unit unit){
        //timer2 is used to store radius scale as an effect
        scratch.get().realRad = radiusScale * radius;
    }
}
