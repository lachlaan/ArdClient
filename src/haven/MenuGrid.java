/*
 *  This file is part of the Haven & Hearth game client.
 *  Copyright (C) 2009 Fredrik Tolf <fredrik@dolda2000.com>, and
 *                     Björn Johannessen <johannessen.bjorn@gmail.com>
 *
 *  Redistribution and/or modification of this file is subject to the
 *  terms of the GNU Lesser General Public License, version 3, as
 *  published by the Free Software Foundation.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  Other parts of this source tree adhere to other copying
 *  rights. Please see the file `COPYING' in the root directory of the
 *  source tree for details.
 *
 *  A copy the GNU Lesser General Public License is distributed along
 *  with the source tree of which this file is a part in the file
 *  `doc/LPGL-3'. If it is missing for any reason, please see the Free
 *  Software Foundation's website at <http://www.fsf.org/>, or write
 *  to the Free Software Foundation, Inc., 59 Temple Place, Suite 330,
 *  Boston, MA 02111-1307 USA
 */

package haven;

import haven.Resource.AButton;
import haven.automation.*;
import haven.automation.BeltDrink;
import haven.automation.Discord;
import haven.purus.*;
import haven.automation.PepperBot;

import java.awt.*;
import java.awt.event.KeyEvent;
import java.awt.font.TextAttribute;
import java.awt.image.BufferedImage;
import haven.Resource.AButton;
import java.util.*;
import java.util.List;

public class MenuGrid extends Widget {
    public final static Coord bgsz = Inventory.invsq.sz().add(-1, -1);
    public final static RichText.Foundry ttfnd = new RichText.Foundry(TextAttribute.FAMILY, Text.cfg.font.get("sans"), TextAttribute.SIZE, Text.cfg.tooltipCap); //aa(true)
    public final Set<Pagina> paginae = new HashSet<Pagina>();
    private static Coord gsz = new Coord(4, 4);
    private Pagina cur, dragging;
    private Collection<PagButton> curbtns = null;
    private PagButton pressed, layout[][] = new PagButton[gsz.x][gsz.y];
    private UI.Grab grab;
    private int curoff = 0;
    private boolean recons = true;
    private Map<Character, PagButton> hotmap = new HashMap<>();
    private boolean togglestuff = true;
    public boolean discordconnected;

    @RName("scm")
    public static class $_ implements Factory {
        public Widget create(UI ui, Object[] args) {
            return (new MenuGrid());
        }
    }

    public static class PagButton implements ItemInfo.Owner {
        public final Pagina pag;
        public final Resource res;

        public PagButton(Pagina pag) {
            this.pag = pag;
            this.res = pag.res();
        }

        public BufferedImage img() {return(res.layer(Resource.imgc).img);}
        public String name() {return(res.layer(Resource.action).name);}
        public char hotkey() {return(res.layer(Resource.action).hk);}
        public void use() {
            pag.scm.wdgmsg("act", (Object[])res.layer(Resource.action).ad);
        }

        public String sortkey() {
            AButton ai = pag.act();
            if(ai.ad.length == 0)
                return("\0" + name());
            return(name());
        }

        private List<ItemInfo> info = null;
        public List<ItemInfo> info() {
            if(info == null)
                info = ItemInfo.buildinfo(this, pag.rawinfo);
            return(info);
        }
        private static final OwnerContext.ClassResolver<PagButton> ctxr = new OwnerContext.ClassResolver<PagButton>()
                .add(Glob.class, p -> p.pag.scm.ui.sess.glob)
                .add(Session.class, p -> p.pag.scm.ui.sess);
        public <T> T context(Class<T> cl) {return(ctxr.context(cl, this));}

        public BufferedImage rendertt(boolean withpg) {
            Resource.AButton ad = res.layer(Resource.action);
            Resource.Pagina pg = res.layer(Resource.pagina);
            String tt = ad.name;
            int pos = tt.toUpperCase().indexOf(Character.toUpperCase(ad.hk));
            if(pos >= 0)
                tt = tt.substring(0, pos) + "$b{$col[255,128,0]{" + tt.charAt(pos) + "}}" + tt.substring(pos + 1);
            else if(ad.hk != 0)
                tt += " [" + ad.hk + "]";
            BufferedImage ret = ttfnd.render(tt, 300).img;
            if(withpg) {
                List<ItemInfo> info = info();
                info.removeIf(el -> el instanceof ItemInfo.Name);
                if(!info.isEmpty())
                    ret = ItemInfo.catimgs(0, ret, ItemInfo.longtip(info));
                if(pg != null)
                    ret = ItemInfo.catimgs(0, ret, ttfnd.render("\n" + pg.text, 200).img);
            }
            return(ret);
        }

        @Resource.PublishedCode(name = "pagina")
        public interface Factory {
            public PagButton make(Pagina info);
        }
    }

    public final PagButton next = new PagButton(new Pagina(this, Resource.local().loadwait("gfx/hud/sc-next").indir())) {
        public void use() {
            if((curoff + 14) >= curbtns.size())
                curoff = 0;
            else
                curoff += 14;
        }

        public BufferedImage rendertt(boolean withpg) {
            return(RichText.render("More... ($b{$col[255,128,0]{\u21e7N}})", 0).img);
        }
    };

    public final PagButton bk = new PagButton(new Pagina(this, Resource.local().loadwait("gfx/hud/sc-back").indir())) {
        public void use() {
            pag.scm.cur = paginafor(pag.scm.cur.act().parent);
            curoff = 0;
        }

        public BufferedImage rendertt(boolean withpg) {
            return(RichText.render("Back ($b{$col[255,128,0]{Backspace}})", 0).img);
        }
    };

    public static class Pagina {
        public final MenuGrid scm;
        public final Indir<Resource> res;
        public State st;
        public double meter, gettime, dtime, fstart;
        public Indir<Tex> img;
        public int newp;
        public Object[] rawinfo = {};

        public static enum State {
            ENABLED, DISABLED {
                public Indir<Tex> img(Pagina pag) {
                    return(Utils.cache(() -> new TexI(PUtils.monochromize(pag.button().img(), Color.LIGHT_GRAY))));
                }
            };

            public Indir<Tex> img(Pagina pag) {
                return(Utils.cache(() -> new TexI(pag.button().img())));
            }
        }

        public Pagina(MenuGrid scm, Indir<Resource> res) {
            this.scm = scm;
            this.res = res;
            state(State.ENABLED);
        }
        
        public Resource res() {
            return(res.get());
        }

        public Resource.AButton act() {
            return(res().layer(Resource.action));
        }

        private PagButton button = null;
        public PagButton button() {
            if(button == null) {
                Resource res = res();
                PagButton.Factory f = res.getcode(PagButton.Factory.class, false);
                if(f == null)
                    button = new PagButton(this);
                else
                    button = f.make(this);
            }
            return(button);
        }

        public void state(State st) {
            this.st = st;
            this.img = st.img(this);
        }
    }

    public Map<Indir<Resource>, Pagina> pmap = new WeakHashMap<Indir<Resource>, Pagina>();
    public Pagina paginafor(Indir<Resource> res) {
        if(res == null)
            return(null);
        synchronized(pmap) {
            Pagina p = pmap.get(res);
            if(p == null)
                pmap.put(res, p = new Pagina(this, res));
            return(p);
        }
    }

    private boolean cons(Pagina p, Collection<PagButton> buf) {
        Pagina[] cp = new Pagina[0];
        Collection<Pagina> open, close = new HashSet<Pagina>();
        synchronized (paginae) {
            open = new LinkedList<Pagina>();
            for (Pagina pag : paginae) {
                if (pag.newp == 2) {
                    pag.newp = 0;
                    pag.fstart = 0;
                }
                open.add(pag);
            }
            for (Pagina pag : pmap.values()) {
                if (pag.newp == 2) {
                    pag.newp = 0;
                    pag.fstart = 0;
                }
            }
        }
        boolean ret = true;
        while (!open.isEmpty()) {
            Iterator<Pagina> iter = open.iterator();
            Pagina pag = iter.next();
            iter.remove();
            try {
                AButton ad = pag.act();
                if (ad == null)
                    throw(new RuntimeException("Pagina in " + pag.res + " lacks action"));
                Pagina parent = paginafor(ad.parent);
                if ((pag.newp != 0) && (parent != null) && (parent.newp == 0)) {
                    parent.newp = 2;
                    parent.fstart = (parent.fstart == 0) ? pag.fstart : Math.min(parent.fstart, pag.fstart);
                }
                if (parent == p)
                    buf.add(pag.button());
                else if ((parent != null) && !close.contains(parent) && !open.contains(parent))
                    open.add(parent);
                close.add(pag);
            } catch (Loading e) {
                ret = false;
            }
        }
        return (ret);
    }

    public MenuGrid() {
        super(bgsz.mul(gsz).add(1, 1));
    }

    @Override
    protected void attach(UI ui) {
        super.attach(ui);
        synchronized (paginae) {
            Collection<Pagina> p = paginae;
            p.add(paginafor(Resource.local().load("paginae/amber/coal8")));
           // p.add(paginafor(Resource.local().load("paginae/amber/coal11")));
           // p.add(paginafor(Resource.local().load("paginae/amber/coal9")));
           // p.add(paginafor(Resource.local().load("paginae/amber/coal12")));
            p.add(paginafor(Resource.local().load("paginae/amber/branchoven")));
           // p.add(paginafor(Resource.local().load("paginae/amber/steel")));
            p.add(paginafor(Resource.local().load("paginae/amber/torch")));
            p.add(paginafor(Resource.local().load("paginae/amber/CoalToSmelters")));
            p.add(paginafor(Resource.local().load("paginae/amber/DestroyArea")));
            p.add(paginafor(Resource.local().load("paginae/amber/Coracleslol")));
            p.add(paginafor(Resource.local().load("paginae/amber/MinerAlert")));
            p.add(paginafor(Resource.local().load("paginae/amber/clover")));
            //p.add(paginafor(Resource.local().load("paginae/amber/rope")));
           // p.add(paginafor(Resource.local().load("paginae/amber/fish")));
            p.add(paginafor(Resource.local().load("paginae/amber/timers")));
            p.add(paginafor(Resource.local().load("paginae/amber/livestock")));
           // p.add(paginafor(Resource.local().load("paginae/amber/shoo")));
            p.add(paginafor(Resource.local().load("paginae/amber/dream")));
            p.add(paginafor(Resource.local().load("paginae/amber/trellisharvest")));
            p.add(paginafor(Resource.local().load("paginae/amber/trellisdestroy")));
            p.add(paginafor(Resource.local().load("paginae/amber/cheesetrayfiller")));
            p.add(paginafor(Resource.local().load("paginae/amber/equipweapon")));
            p.add(paginafor(Resource.local().load("paginae/amber/BeltDrink")));
            p.add(paginafor(Resource.local().load("paginae/amber/SliceCheese")));
            p.add(paginafor(Resource.local().load("paginae/amber/SplitLogs")));
            p.add(paginafor(Resource.local().load("paginae/amber/OpenRacks")));
            p.add(paginafor(Resource.local().load("paginae/amber/CountGobs")));
            p.add(paginafor(Resource.local().load("paginae/amber/Discord")));
            p.add(paginafor(Resource.local().load("paginae/amber/PepperBot")));
            p.add(paginafor(Resource.local().load("paginae/amber/TakeTrays")));
            p.add(paginafor(Resource.local().load("paginae/amber/CraftAllBot")));
            p.add(paginafor(Resource.local().load("paginae/amber/PepperFood")));
            p.add(paginafor(Resource.local().load("paginae/amber/PlaceTrays")));
           // p.add(paginafor(Resource.local().load("paginae/amber/dismount")));
            // Purus Pasta
            p.add(paginafor(Resource.local().load("paginae/purus/farmer")));
            p.add(paginafor(Resource.local().load("paginae/purus/troughfill")));
            p.add(paginafor(Resource.local().load("paginae/purus/study")));
           // p.add(paginafor(Resource.local().load("paginae/purus/barrelfill")));
            p.add(paginafor(Resource.local().load("paginae/purus/stockpilefill")));
            // PBot Scripts
            p.add(paginafor(Resource.local().load("paginae/purus/PBotMenu")));
        }
    }

    private void updlayout() {
        synchronized(paginae) {
            List<PagButton> cur = new ArrayList<>();
            recons = !cons(this.cur, cur);
            Collections.sort(cur, Comparator.comparing(PagButton::sortkey));
            this.curbtns = cur;
            int i = curoff;
            hotmap.clear();
            for(PagButton btn : cur) {
                char hk = btn.hotkey();
                if(hk != 0)
                    hotmap.put(Character.toUpperCase(hk), btn);
            }
            for(int y = 0; y < gsz.y; y++) {
                for(int x = 0; x < gsz.x; x++) {
                    PagButton btn = null;
                    if((this.cur != null) && (x == gsz.x - 1) && (y == gsz.y - 1)) {
                        btn = bk;
                    } else if ((cur.size() - curoff > gsz.x * gsz.y - 1) && (x == gsz.x - 2) && (y == gsz.y - 1)) {
                        btn = next;
                    } else if(i < cur.size()) {
                        btn = cur.get(i++);
                    }
                    layout[x][y] = btn;
                }
            }
        }
    }

    private static Map<PagButton, Tex> glowmasks = new WeakHashMap<>();

    private Tex glowmask(PagButton pag) {
        Tex ret = glowmasks.get(pag);
        if (ret == null) {
            ret = new TexI(PUtils.glowmask(PUtils.glowmask(pag.img().getRaster()), 4, new Color(32, 255, 32)));
            glowmasks.put(pag, ret);
        }
        return (ret);
    }

    public void draw(GOut g) {
        double now = Utils.rtime();
        for(int y = 0; y < gsz.y; y++) {
            for(int x = 0; x < gsz.x; x++) {
                Coord p = bgsz.mul(new Coord(x, y));
                g.image(Inventory.invsq, p);
                PagButton btn = layout[x][y];
                if(btn != null) {
                    Pagina info = btn.pag;
                    Tex btex = info.img.get();
                    g.image(btex, p.add(1, 1));
                    if(info.meter > 0) {
                        double m = info.meter;
                        if(info.dtime > 0)
                            m += (1 - m) * (now - info.gettime) / info.dtime;
                        m = Utils.clip(m, 0, 1);
                        g.chcolor(255, 255, 255, 128);
                        g.fellipse(p.add(bgsz.div(2)), bgsz.div(2), Math.PI / 2, ((Math.PI / 2) + (Math.PI * 2 * m)));
                        g.chcolor();
                    }
                    if(info.newp != 0) {
                        if(info.fstart == 0) {
                            info.fstart = now;
                        } else {
                            double ph = (now - info.fstart) - (((x + (y * gsz.x)) * 0.15) % 1.0);
                            if(ph < 1.25) {
                                g.chcolor(255, 255, 255, (int)(255 * ((Math.cos(ph * Math.PI * 2) * -0.5) + 0.5)));
                                g.image(glowmask(btn), p.sub(4, 4));
                                g.chcolor();
                            } else {
                                g.chcolor(255, 255, 255, 128);
                                g.image(glowmask(btn), p.sub(4, 4));
                                g.chcolor();
                            }
                        }
                    }
                    if(btn == pressed) {
                        g.chcolor(new Color(0, 0, 0, 128));
                        g.frect(p.add(1, 1), btex.sz());
                        g.chcolor();
                    }
                }
            }
        }
        super.draw(g);
        if(dragging != null) {
            Tex dt = dragging.img.get();
            ui.drawafter(new UI.AfterDraw() {
                public void draw(GOut g) {
                    g.image(dt, ui.mc.add(dt.sz().div(2).inv()));
                }
            });
        }
    }

    private PagButton curttp = null;
    private boolean curttl = false;
    private Tex curtt = null;
    private double hoverstart;

    public Object tooltip(Coord c, Widget prev) {
        PagButton pag = bhit(c);
        double now = Utils.rtime();
        if(pag != null) {
            if(prev != this)
                hoverstart = now;
            boolean ttl = (now - hoverstart) > 0.5;
            if((pag != curttp) || (ttl != curttl)) {
                try {
                    BufferedImage ti = pag.rendertt(ttl);
                    curtt = (ti == null) ? null : new TexI(ti);
                } catch(Loading l) {
                    return(null);
                }
                curttp = pag;
                curttl = ttl;
            }
            return(curtt);
        } else {
            hoverstart = now;
            return(null);
        }
    }

    private PagButton bhit(Coord c) {
        Coord bc = c.div(bgsz);
        if ((bc.x >= 0) && (bc.y >= 0) && (bc.x < gsz.x) && (bc.y < gsz.y))
            return (layout[bc.x][bc.y]);
        else
            return (null);
    }

    public boolean mousedown(Coord c, int button) {
        PagButton h = bhit(c);
        if ((button == 1) && (h != null)) {
            pressed = h;
            grab = ui.grabmouse(this);
        }
        return (true);
    }

    public void mousemove(Coord c) {
        if ((dragging == null) && (pressed != null)) {
            PagButton h = bhit(c);
            if (h != pressed)
                dragging = pressed.pag;
        }
    }

    public void use(String[] ad) {
        GameUI gui = gameui();
        if (gui == null)
            return;
        if (ad[1].equals("coal")) {
            Thread t = new Thread(new AddCoalToSmelter(gui, Integer.parseInt(ad[2])), "AddCoalToSmelter");
            t.start();
        } else if (ad[1].equals("branchoven")) {
            Thread t = new Thread(new AddBranchesToOven(gui, Integer.parseInt(ad[2])), "AddBranchesToOven");
            t.start();
        } else if (ad[1].equals("steel")) {
            if (gui.getwnd("Steel Refueler") == null) {
                SteelRefueler sw = new SteelRefueler();
                gui.map.steelrefueler = sw;
                gui.add(sw, new Coord(gui.sz.x / 2 - sw.sz.x / 2, gui.sz.y / 2 - sw.sz.y / 2 - 200));
                synchronized (GobSelectCallback.class) {
                    gui.map.registerGobSelect(sw);
                }
            }
        } else if (ad[1].equals("torch")) {
        if (gui.getwnd("Torch Lighter") == null) {
            LightWithTorch sw = new LightWithTorch(gui);
            gui.map.torchlight = sw;
            gui.add(sw, new Coord(gui.sz.x / 2 - sw.sz.x / 2, gui.sz.y / 2 - sw.sz.y / 2 - 200));
            synchronized (GobSelectCallback.class) {
                gui.map.registerGobSelect(sw);
            }
        }
    } else if (ad[1].equals("destroyarea")) {
        if (gui.getwnd("Destroy Gobs in Area") == null) {
            DestroyArea sw = new DestroyArea(gui);
            gui.map.destroyarea = sw;
            gui.add(sw, new Coord(gui.sz.x / 2 - sw.sz.x / 2, gui.sz.y / 2 - sw.sz.y / 2 - 200));
            synchronized (GobSelectCallback.class) {
                gui.map.registerGobSelect(sw);
            }
        }
    }
     else if (ad[1].equals("coaltosmelters")) {
            if (gui.getwnd("Add Coal To Smelters") == null) {
                CoalToSmelters sw = new CoalToSmelters(gui);
                gui.map.coaltosmelters = sw;
                gui.add(sw, new Coord(gui.sz.x / 2 - sw.sz.x / 2, gui.sz.y / 2 - sw.sz.y / 2 - 200));
                synchronized (GobSelectCallback.class) {
                    gui.map.registerGobSelect(sw);
                }
            }
    } else if (ad[1].equals("PepperBot")) {
            if(gui.getwnd("Pepper Bot" ) == null){
            PepperBot sw = new PepperBot(gui);
            gui.map.pepperbot = sw;
            gui.add(sw, new Coord(gui.sz.x / 2 - sw.sz.x / 2, gui.sz.y / 2 - sw.sz.y / 2 - 200));
            synchronized (GobSelectCallback.class) {
                gui.map.registerAreaSelect (sw);
                gui.map.registerGobSelect(sw);
            }
            }
        } else if (ad[1].equals("MinerAlert")) {
            if (gui.getwnd("Miner Alert") == null) {
                MinerAlert sw = new MinerAlert(gui);
                gui.map.mineralert = sw;
                gui.add(sw, new Coord(gui.sz.x / 2 - sw.sz.x / 2, gui.sz.y / 2 - sw.sz.y / 2 - 200));
            }
        }
        else if (ad[1].equals("CraftAllBot")) {
            if (gui.getwnd("CraftAllBot") == null) {
                CraftAllBot sw = new CraftAllBot(gui);
                gui.map.craftbot = sw;
                gui.add(sw, new Coord(gui.sz.x / 2 - sw.sz.x / 2, gui.sz.y / 2 - sw.sz.y / 2 - 200));
                synchronized (GobSelectCallback.class) {
                    gui.map.registerGobSelect(sw);
                }
            }
        } else if (ad[1].equals("timers")) {
            gui.timerswnd.show(!gui.timerswnd.visible);
            gui.timerswnd.raise();
        } else if (ad[1].equals("clover")) {
            new Thread(new FeedClover(gui), "FeedClover").start();
        } else if (ad[1].equals("fish")) {
            new Thread(new ButcherFish(gui), "ButcherFish").start();
        } else if (ad[1].equals("rope")) {
            new Thread(new LeashAnimal(gui), "LeashAnimal").start();
        } else if (ad[1].equals("livestock")) {
            gui.livestockwnd.show(!gui.livestockwnd.visible);
            gui.livestockwnd.raise();
        } else if (ad[1].equals("shoo")) {
            new Thread(new Shoo(gui), "Shoo").start();
        } else if (ad[1].equals("Coracleslol")) {
            new Thread(new Coracleslol(gui), "Coracleslol").start();
        } else if (ad[1].equals("dream")) {
            new Thread(new DreamHarvester(gui), "DreamHarvester").start();
        } else if (ad[1].equals("trellis-harvest")) {
            new Thread(new TrellisHarvest(gui), "TrellisHarvest").start();
        } else if (ad[1].equals("trellis-destroy")) {
            new Thread(new TrellisDestroy(gui), "TrellisDestroy").start();
        } else if (ad[1].equals("cheesetray-fill")) {
            new Thread(new FillCheeseTray(gui), "FillCheeseTray").start();
        } else if (ad[1].equals("equipweapon")) {
            new Thread(new EquipWeapon(gui), "EquipWeapon").start();
        } else if (ad[1].equals("BeltDrink")) {
            new Thread(new BeltDrink(gui), "BeltDrink").start();
        } else if (ad[1].equals("CountGobs")) {
            new Thread(new CountGobs(gui), "CountGobs").start();
        } else if (ad[1].equals("Discord")) {
            if (discordconnected) {
                discordconnected = false;
                BotUtils.sysMsg("Discord Disconnected",Color.white);
                Discord.jdalogin.shutdownNow();
                for(int i=0;i<15;i++) {
                    for (Widget w = gui.chat.lchild; w != null; w = w.prev) {
                        if (w instanceof ChatUI.DiscordChat) {
                            w.destroy();
                        }
                    }
                }
            }
            else
            new Thread(new Discord(gui)).start();
        } else if (ad[1].equals("TakeTrays")) {
            new Thread(new TakeTrays(gui), "TakeTrays").start();
        } else if (ad[1].equals("PepperFood")) {
            new Thread(new PepperFood(gui), "PepperFood").start();
        } else if (ad[1].equals("PlaceTrays")) {
            new Thread(new PlaceTrays(gui), "PlaceTrays").start();
        } else if (ad[1].equals("OpenRacks")) {
            new Thread(new OpenRacks(gui), "OpenRacks").start();
        } else if (ad[1].equals("SliceCheese")) {
            new Thread(new SliceCheese(gui), "SliceCheese").start();
        } else if (ad[1].equals("SplitLogs")) {
            new Thread(new SplitLogs(gui), "SplitLogs").start();
        } else if (ad[1].equals("dismount")) {
            new Thread(new Dismount(gui), "Dismount").start();
        } else if(ad[1].equals("farmbot")) {
        	Farmer f = new Farmer();
        	Window w = f;
        	gui.add(w, new Coord(gui.sz.x/2 - w.sz.x/2, gui.sz.y/2 - w.sz.y/2 - 200));
            synchronized (GobSelectCallback.class) {
                gameui().map.registerAreaSelect(f);
            }

        } else if(ad[1].equals("troughfill")) {
        	TroughFiller tf = new TroughFiller();
        	gui.add(tf, new Coord(gui.sz.x / 2 - tf.sz.x / 2, gui.sz.y / 2 - tf.sz.y / 2 - 200));
            synchronized (GobSelectCallback.class) {
                gui.map.registerGobSelect(tf);
            }
        } else if(ad[1].equals("study")) {
        	if(ui.gui != null)
        		ui.gui.toggleStudy();
        } else if(ad[1].equals("barrelfill")) {
        	BarrelFiller bf = new BarrelFiller();
        	gui.add(bf, new Coord(gui.sz.x / 2 - bf.sz.x / 2, gui.sz.y / 2 - bf.sz.y / 2 - 200));
            synchronized (GobSelectCallback.class) {
                gui.map.registerGobSelect(bf);
            }
        } else if(ad[1].equals("stockpilefill")) {
        	StockpileFiller spf = new StockpileFiller();
        	gui.add(spf, new Coord(gui.sz.x / 2 - spf.sz.x / 2, gui.sz.y / 2 - spf.sz.y / 2 - 200));
            synchronized (GobSelectCallback.class) {
        		gui.map.registerGobSelect(spf);
        	}
        } else if(ad[1].equals("PBotList")) {
            if (gui.PBotScriptlist.show(!gui.PBotScriptlist.visible)) {
            	gui.PBotScriptlist.raise();
            	gui.fitwdg(gui.PBotScriptlist);
                setfocus(gui.PBotScriptlist);
            }
        }
    }

    private void use(PagButton r, boolean reset) {
        Collection<PagButton> sub = new ArrayList<>();
        cons(r.pag, sub);
        if (sub.size() > 0) {
            this.cur = r.pag;
            curoff = 0;
        } else {
            r.pag.newp = 0;
            Resource.AButton act = r.pag.act();
            if (act == null) {
                r.use();
            } else {
                String[] ad = r.pag.act().ad;
                if (ad[0].equals("@")) {
                    use(ad);
                } else {
                    if (ad.length > 0 && (ad[0].equals("craft") || ad[0].equals("bp")))
                        gameui().histbelt.push(r.pag);

                    if (Config.confirmmagic && r.res.name.startsWith("paginae/seid/") && !r.res.name.equals("paginae/seid/rawhide")) {
                        Window confirmwnd = new Window(new Coord(225, 100), "Confirm") {
                            @Override
                            public void wdgmsg(Widget sender, String msg, Object... args) {
                                if (sender == cbtn)
                                    reqdestroy();
                                else
                                    super.wdgmsg(sender, msg, args);
                            }

                            @Override
                            public boolean type(char key, KeyEvent ev) {
                                if (key == 27) {
                                    reqdestroy();
                                    return true;
                                }
                                return super.type(key, ev);
                            }
                        };

                        confirmwnd.add(new Label(Resource.getLocString(Resource.BUNDLE_LABEL, "Using magic costs experience points. Are you sure you want to proceed?")),
                                new Coord(10, 20));
                        confirmwnd.pack();

                        MenuGrid mg = this;
                        Button yesbtn = new Button(70, "Yes") {
                            @Override
                            public void click() {
                                r.use();
                                parent.reqdestroy();
                            }
                        };
                        confirmwnd.add(yesbtn, new Coord(confirmwnd.sz.x / 2 - 60 - yesbtn.sz.x, 60));
                        Button nobtn = new Button(70, "No") {
                            @Override
                            public void click() {
                                parent.reqdestroy();
                            }
                        };
                        confirmwnd.add(nobtn, new Coord(confirmwnd.sz.x / 2 + 20, 60));
                        confirmwnd.pack();

                        GameUI gui = gameui();
                        gui.add(confirmwnd, new Coord(gui.sz.x / 2 - confirmwnd.sz.x / 2, gui.sz.y / 2 - 200));
                        confirmwnd.show();
                    } else {
                        r.use();
                    }
                }
            }

            if (reset)
                this.cur = null;
        }
        updlayout();
    }

    public void tick(double dt) {
        if (recons)
            updlayout();

        if (togglestuff) {
            GameUI gui = gameui();
            if (Config.enabletracking && !GameUI.trackon) {
                wdgmsg("act", new Object[]{"tracking"});
                gui.trackautotgld = true;
            }
            if(Config.autoconnectdiscord && !discordconnected) {
                if (Resource.getLocString(Resource.BUNDLE_LABEL, Config.discordbotkey) != null) {
                   new Thread(new Discord(gui)).start();
                   discordconnected = true;
                }
            }
            if (Config.enablecrime && !GameUI.crimeon) {
                gui.crimeautotgld = true;
                wdgmsg("act", new Object[]{"crime"});
            }
            for (Widget w = gameui().chat.lchild; w != null; w = w.prev) {
                if (w instanceof ChatUI.MultiChat) {
                    ChatUI.MultiChat chat = (ChatUI.MultiChat) w;
                    if(Config.chatalert != null) {
                        if (chat.name().equals(Resource.getLocString(Resource.BUNDLE_LABEL, Config.chatalert))) {
                            chat.select();
                            chat.getparent(ChatUI.class).expand();
                            break;
                        }
                    }
                    else
                    if (chat.name().equals(Resource.getLocString(Resource.BUNDLE_LABEL, "Area Chat"))) {
                        chat.select();
                        chat.getparent(ChatUI.class).expand();
                        break;
                    }
                }
            }
            togglestuff = false;
        }
    }

    public boolean mouseup(Coord c, int button) {
        PagButton h = bhit(c);
        if ((button == 1) && (grab != null)) {
            if (dragging != null) {
                ui.dropthing(ui.root, ui.mc, dragging.res());
                pressed = null;
                dragging = null;
            } else if (pressed != null) {
                if (pressed == h)
                    use(h, false);
                pressed = null;
            }
            grab.remove();
            grab = null;
        }
        return (true);
    }

    public void uimsg(String msg, Object... args) {
        if(msg == "goto") {
            if(args[0] == null)
                cur = null;
            else
                cur = paginafor(ui.sess.getres((Integer)args[0]));
            curoff = 0;
            updlayout();
        } else if(msg == "fill") {
            synchronized(paginae) {
                int a = 0;
                while(a < args.length) {
                    int fl = (Integer)args[a++];
                    Pagina pag = paginafor(ui.sess.getres((Integer)args[a++]));
                    if((fl & 1) != 0) {
                        pag.state(Pagina.State.ENABLED);
                        pag.meter = 0;
                        if((fl & 2) != 0)
                            pag.state(Pagina.State.DISABLED);
                        if((fl & 4) != 0) {
                            pag.meter = ((Number)args[a++]).doubleValue() / 1000.0;
                            pag.gettime = Utils.rtime();
                            pag.dtime = ((Number)args[a++]).doubleValue() / 1000.0;
                        }
                        if((fl & 8) != 0)
                            pag.newp = 1;
                        if((fl & 16) != 0)
                            pag.rawinfo = (Object[])args[a++];
                        else
                            pag.rawinfo = new Object[0];

                        // this is very crappy way to do this. needs to be redone probably
                        try {
                            Resource res = pag.res.get();
                            if (res.name.equals("ui/tt/q/quality") || res.name.equals("gfx/fx/msrad"))
                                continue;
                        } catch (Loading l) {
                        }

                        paginae.add(pag);
                    } else {
                        paginae.remove(pag);
                    }
                }
                updlayout();
            }
        } else {
            super.uimsg(msg, args);
        }
    }

    public boolean globtype(char k, KeyEvent ev) {
        if (ev.isShiftDown() || ev.isAltDown()) {
            return false;
        } else if ((k == 27) && (this.cur != null)) {
            this.cur = null;
            curoff = 0;
            updlayout();
            return (true);
        } else if ((k == 8) && (this.cur != null)) {
            this.cur = paginafor(this.cur.act().parent);
            curoff = 0;
            updlayout();
            return (true);
        } else if ((k == 'N') && (layout[gsz.x - 2][gsz.y - 1] == next)) {
            use(next, false);
            return (true);
        }
        PagButton r = hotmap.get(Character.toUpperCase(k));
        if (r != null) {
            use(r, true);
            return (true);
        }
        return (false);
    }
}
