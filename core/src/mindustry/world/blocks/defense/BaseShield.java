package mindustry.world.blocks.defense;

import arc.func.*;
import arc.graphics.*;
import arc.graphics.g2d.*;
import arc.math.*;
import arc.util.*;
import arc.util.io.*;
import mindustry.content.*;
import mindustry.entities.*;
import mindustry.game.*;
import mindustry.gen.*;
import mindustry.graphics.*;
import mindustry.world.*;

import static mindustry.Vars.*;

public class BaseShield extends Block{
    //TODO game rule? or field? should vary by base.
    public float radius = 200f;
    public int sides = 24;

    public @Nullable Color shieldColor;

    protected static final class ShieldScratch{ BaseShieldBuild paramBuild; }
    protected static final ThreadLocal<ShieldScratch> shieldScratch = ThreadLocal.withInitial(ShieldScratch::new);
    //protected static Effect paramEffect;
    protected static final Cons<Bullet> bulletConsumer = bullet -> {
        BaseShieldBuild paramBuild = shieldScratch.get().paramBuild;
        if(paramBuild != null && bullet.team != paramBuild.team && bullet.type.absorbable && bullet.within(paramBuild, paramBuild.radius())){
            bullet.absorb();
            //paramEffect.at(bullet);

            //TODO effect, shield health go down?
            //paramBuild.hit = 1f;
            //paramBuild.buildup += bullet.damage;
        }
    };

    protected static final Cons<Unit> unitConsumer = unit -> {
        BaseShieldBuild paramBuild = shieldScratch.get().paramBuild;
        if(paramBuild == null) return;
        //if this is positive, repel the unit; if it exceeds the unit radius * 2, it's inside the forcefield and must be killed
        float overlapDst = (unit.hitSize/2f + paramBuild.radius()) - unit.dst(paramBuild);

        if(overlapDst > 0){
            if(overlapDst > unit.hitSize * 1.5f){
                //instakill units that are stuck inside the shield (TODO or maybe damage them instead?)
                unit.kill();
            }else{
                //stop
                unit.vel.setZero();
                //get out
                unit.move(Tmp.v1().set(unit).sub(paramBuild).setLength(overlapDst + 0.01f));

                if(Mathf.chanceDelta(0.12f * Time.delta())){
                    Fx.circleColorSpark.at(unit.x, unit.y, paramBuild.team.color);
                }
            }
        }
    };

    public BaseShield(String name){
        super(name);

        hasPower = true;
        update = solid = true;
        rebuildable = false;
        allowedInPayloads = false;
    }

    @Override
    public void init(){
        super.init();

        updateClipRadius(radius);
    }

    @Override
    public void drawPlace(int x, int y, int rotation, boolean valid){
        super.drawPlace(x, y, rotation, valid);

        Drawf.dashCircle(x * tilesize + offset, y * tilesize + offset, radius, player.team().color);
    }

    public class BaseShieldBuild extends Building{
        public boolean broken = false;
        public float hit = 0f;
        public float smoothRadius;

        @Override
        public void updateTile(){
            smoothRadius = Mathf.lerpDelta(smoothRadius, radius * efficiency, 0.05f);

            float rad = radius();

            if(rad > 1){
                ShieldScratch scratch = shieldScratch.get();
                scratch.paramBuild = this;
                try{
                    //paramEffect = absorbEffect;
                    Groups.current().bullet.intersect(x - rad, y - rad, rad * 2f, rad * 2f, bulletConsumer);
                    Units.nearbyEnemies(team, x, y, rad + 10f, unitConsumer);
                }finally{ scratch.paramBuild = null; }
            }
        }

        public float radius(){
            return smoothRadius;
        }

        @Override
        public void drawSelect(){
            super.drawSelect();

            Drawf.dashCircle(x, y, radius, team.color);
        }

        @Override
        public void draw(){
            super.draw();

            drawShield();
        }

        //always visible due to their shield nature
        @Override
        public boolean inFogTo(Team viewer){
            return false;
        }

        public void drawShield(){
            if(!broken){
                float radius = radius();

                Draw.z(Layer.shields);

                Draw.color(shieldColor == null ? team.color : shieldColor, Color.white, Mathf.clamp(hit));

                if(renderer.animateShields){
                    Fill.poly(x, y, sides, radius);
                }else{
                    Lines.stroke(1.5f);
                    Draw.alpha(0.09f + Mathf.clamp(0.08f * hit));
                    Fill.poly(x, y, sides, radius);
                    Draw.alpha(1f);
                    Lines.poly(x, y, sides, radius);
                    Draw.reset();
                }
            }

            Draw.reset();
        }

        @Override
        public byte version(){
            return 1;
        }

        @Override
        public void write(Writes write){
            super.write(write);

            write.f(smoothRadius);
            write.bool(broken);
        }

        @Override
        public void read(Reads read, byte revision){
            super.read(read);

            if(revision >= 1){
                smoothRadius = read.f();
                broken = read.bool();
            }
        }
    }
}
