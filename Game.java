import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.*;
import javax.imageio.ImageIO;
import javax.swing.*;


class Entity {
    static final int CUST_HP = 120;
    static final int CUST_DMG = 20;
    int x, y, hp, maxHP;
    BufferedImage image;
    public Entity(int x, int y, int maxHP) { this.x = x; this.y = y; this.maxHP = maxHP; this.hp = maxHP; }
}

class Player extends Entity {
    public Player(int x, int y) { super(x, y, CUST_HP); }
    public void reset() { this.maxHP = CUST_HP; this.hp = CUST_HP; }
}

class Monster extends Entity {
    boolean isBoss;
    public Monster(int x, int y, int mapLevel, boolean isBoss) {
        super(x, y, isBoss ? 280 : 75 + (mapLevel * 15));
        this.isBoss = isBoss;
    }
}

class Inventory {
    private boolean hasKey = false;
    private double dmgBoost = 1.0;

    public boolean hasKey() { return hasKey; }
    public void setHasKey(boolean hasKey) { this.hasKey = hasKey; }
    public double getDmgBoost() { return dmgBoost; }
    public int getDmgBoostPercent() { return (int) Math.round((dmgBoost - 1.0) * 100); }
    public void reset() { hasKey = false; dmgBoost = 1.0; }
    public void collectKey() { hasKey = true; }
    public String collectChest() { dmgBoost += 0.05; return "+5% DAMAGE BOOST GAINED!"; }
    public String collectPotion(Player p) {
        if (p.hp < p.maxHP) {
            int heal = Math.min(37, p.maxHP - p.hp);
            p.hp += heal;
            return "HEALED FOR +" + heal + " HP!";
        }
        return "POTION CONSUMED (FULL HP)";
    }
}

public class Game extends JPanel implements Runnable, KeyListener {
    // The game is rendered at this virtual resolution and scaled to fit the window/screen.
    // This keeps the map, HUD and menus proportional in both windowed and fullscreen modes.
    static final int TILE_SIZE = 48, HUD_HEIGHT = 100, SCREEN_WIDTH = 768, SCREEN_HEIGHT = 676;

    JFrame window;
    boolean fullscreen = false;
    Rectangle windowedBounds;
    int windowedExtendedState = JFrame.NORMAL;
    static final char WALL = '1', KEY = '2', DOOR = '3', CHEST = '4', POTION = 'P', MONSTER = 'M';
    static final String HIGHSCORE_FILE = "HIGHSCORE.csv";

    int[][] map;
    int mapWidth, mapHeight, currentMap = 0;
    Player player = new Player(1, 1);
    Monster monster;

    // Player sprite-sheet animation
    BufferedImage playerSheet;
    static final int PLAYER_FRAME_WIDTH = 40;
    static final int PLAYER_FRAME_HEIGHT = 64;
    int playerFrame = 0;
    int playerDirection = 0; // 0=down, 1=left, 2=up, 3=right
    long lastAnimationTime = 0;
    static final long ANIMATION_DELAY = 120;
    Inventory inventory = new Inventory();

    String combatMsgPlayer = "", combatMsgEnemy = "";
    boolean up, down, left, right, gameOver = false, gameWon = false;

    // Pause system
    boolean paused = false;
    long pauseStartTime = 0;
    long totalPausedMillis = 0;

    // Enemy movement system
    long lastEnemyMoveTime = 0;
    static final long ENEMY_MOVE_DELAY = 700;
    int enemyHomeX = -1, enemyHomeY = -1;
    int enemyMoveRange = 2;

    // Combat used to resolve every single 100ms game tick with no cooldown, so fighting
    // any monster with enough HP to take more than a couple of ticks to kill was an
    // automatic death sentence -- you'd take a hit every tick for the whole fight.
    // This cooldown paces combat exchanges the same way enemy movement is already paced.
    long lastCombatTime = 0;
    static final long COMBAT_DELAY = 650;

    enum GameState { MENU, PLAYING, INSTRUCTIONS }
    GameState gameState = GameState.MENU;
    int menuSelection = 0;
    boolean showStats = false;
    String[] mapPaths = {"maps/map1.txt", "maps/map2.txt", "maps/map3.txt", "maps/map4.txt", "maps/map5.txt", "maps/map6.txt", "maps/map7.txt", "maps/boss.txt"};
    Random rand = new Random();

    // Timer & High Score Tracking
    long startTime;
    long elapsedTime = 0;
    long bestTimeSeconds = -1;

    // Every successful run (run number + completion time), kept sorted fastest-first.
    static class RunRecord {
        final int run;
        final long seconds;
        RunRecord(int run, long seconds) { this.run = run; this.seconds = seconds; }
    }
    ArrayList<RunRecord> runRecords = new ArrayList<>();
    boolean highScoreLoadFailed = false; // true if an existing CSV could not be read

    BufferedImage keyImg, doorImg, chestImg, potionImg, wallImg, monsterImg, bossImg;
    BufferedImage[] monsterTints; // per-level recolor of the same sprite, so enemies aren't visually identical across maps
    Color[] levelTintColors = {
        null,                         // map1: original sprite, untinted
        new Color(120, 220, 120),     // map2: green
        new Color(120, 170, 255),     // map3: blue
        new Color(255, 190, 90),      // final: amber
        new Color(210, 130, 255),     // map4: violet
        new Color(255, 140, 140),     // map5: rose
        new Color(160, 160, 160)      // map6: ash grey
    };

    public Game() {
        setPreferredSize(new Dimension(SCREEN_WIDTH, SCREEN_HEIGHT));
        setFocusable(true);
        addKeyListener(this);
        loadImages();
        loadHighScore();
        // Start at the main menu. The first map is loaded when New Game is selected.
        requestFocusInWindow();
    }

    void loadImages() {
        keyImg = loadImage("objects/key.png");
        doorImg = loadImage("objects/door.png");
        chestImg = loadImage("objects/chest.png");
        potionImg = loadImage("objects/potion.png");
        wallImg = loadImage("tiles/wall.png");
        // player/player.png should be the 160x192 sprite sheet (4 columns x 3 rows).
        // If you use the larger 240x448 sheet, the same animation code will use its first
        // three rows automatically.
        playerSheet = loadImage("player/player.png");
        player.image = playerSheet;
        monsterImg = loadImage("objects/monster.png");

        // Boss gets a strong red tint so it reads as a different enemy without needing new art.
        bossImg = tintImage(monsterImg, new Color(220, 30, 30), 0.55f);

        // Pre-generate a tinted variant of the monster sprite for each level.
        monsterTints = new BufferedImage[levelTintColors.length];
        for (int i = 0; i < levelTintColors.length; i++) {
            monsterTints[i] = (levelTintColors[i] == null) ? monsterImg : tintImage(monsterImg, levelTintColors[i], 0.4f);
        }
    }

    // Blends `tint` into every non-transparent pixel of src. strength 0=no change, 1=fully replaced by tint.
    BufferedImage tintImage(BufferedImage src, Color tint, float strength) {
        if (src == null) return null;
        int w = src.getWidth(), h = src.getHeight();
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int argb = src.getRGB(x, y);
                int a = (argb >> 24) & 0xff;
                if (a == 0) { out.setRGB(x, y, argb); continue; }
                int r = (argb >> 16) & 0xff, g = (argb >> 8) & 0xff, b = argb & 0xff;
                r = (int) (r * (1 - strength) + tint.getRed() * strength);
                g = (int) (g * (1 - strength) + tint.getGreen() * strength);
                b = (int) (b * (1 - strength) + tint.getBlue() * strength);
                out.setRGB(x, y, (a << 24) | (r << 16) | (g << 8) | b);
            }
        }
        return out;
    }

    BufferedImage loadImage(String path) {
        try {
            File f = new File(path);
            if (f.exists()) return ImageIO.read(f);
            InputStream is = getClass().getClassLoader().getResourceAsStream(path);
            if (is != null) return ImageIO.read(is);
        } catch (IOException ignored) {}
        return null;
    }

    // Frame counts per row. Row 2 ("up") only has 3 real frames in this sheet — its 4th
    // column is fully transparent, which was causing the player to flash invisible while
    // walking up. Clamping per-row like this instead of using the sheet's raw width fixes it.
    static final int[] FRAME_COUNTS_BY_ROW = {4, 4, 3};

    void updatePlayerAnimation() {
        if (playerSheet == null) return;

        long now = System.currentTimeMillis();
        if (now - lastAnimationTime >= ANIMATION_DELAY) {
            int row = (playerDirection == 3) ? 1 : playerDirection;
            row = Math.min(row, FRAME_COUNTS_BY_ROW.length - 1);
            int frameCount = FRAME_COUNTS_BY_ROW[row];
            playerFrame = (playerFrame + 1) % frameCount;
            lastAnimationTime = now;
        }
    }

    void drawPlayerSprite(Graphics g, int px, int py, int drawW, int drawH) {
        if (playerSheet == null) {
            g.setColor(Color.BLUE);
            g.fillOval(px + 8, py + 8, drawW - 16, drawH - 16);
            return;
        }

        int rows = playerSheet.getHeight() / PLAYER_FRAME_HEIGHT;
        if (rows <= 0) return;

        int row = (playerDirection == 3) ? 1 : playerDirection;
        row = Math.min(row, rows - 1);
        int frameCount = FRAME_COUNTS_BY_ROW[Math.min(row, FRAME_COUNTS_BY_ROW.length - 1)];
        int col = Math.min(playerFrame, frameCount - 1);

        int sx = col * PLAYER_FRAME_WIDTH;
        int sy = row * PLAYER_FRAME_HEIGHT;

        // The sheet has a left-facing walking row. Flip it horizontally for right movement.
        if (playerDirection == 3) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.translate(px + drawW, py);
            g2.scale(-1, 1);
            g2.drawImage(playerSheet,
                    0, 0, drawW, drawH,
                    sx, sy, sx + PLAYER_FRAME_WIDTH, sy + PLAYER_FRAME_HEIGHT,
                    null);
            g2.dispose();
        } else {
            g.drawImage(playerSheet,
                    px, py, px + drawW, py + drawH,
                    sx, sy, sx + PLAYER_FRAME_WIDTH, sy + PLAYER_FRAME_HEIGHT,
                    null);
        }
    }

    // Loads every recorded run from HIGHSCORE.csv, sorts them fastest-first and sets
    // bestTimeSeconds to the lowest time (or -1 if there are no records / file is missing).
    void loadHighScore() {
        runRecords.clear();
        bestTimeSeconds = -1;
        highScoreLoadFailed = false;

        File file = new File(HIGHSCORE_FILE);
        if (!file.exists()) return; // first launch: no history yet

        try (BufferedReader br = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.replace("\uFEFF", "").trim();
                if (line.isEmpty()) continue;
                String[] parts = line.split(",");
                if (parts.length < 2) continue;
                try {
                    int run = Integer.parseInt(parts[0].trim());
                    long secs = Long.parseLong(parts[1].trim());
                    if (run > 0 && secs >= 0) runRecords.add(new RunRecord(run, secs));
                } catch (NumberFormatException ignored) {
                    // header row ("Run,TimeSeconds") or a malformed row: skip it
                }
            }
        } catch (Exception e) {
            highScoreLoadFailed = true;
            System.err.println("Failed to read " + HIGHSCORE_FILE + ": " + e.getMessage());
        }

        sortRunRecords();
        if (!runRecords.isEmpty()) bestTimeSeconds = runRecords.get(0).seconds;
    }

    void sortRunRecords() {
        // Ascending by time (fastest first); ties keep the earlier run first.
        runRecords.sort(Comparator.<RunRecord>comparingLong(r -> r.seconds)
                                  .thenComparingInt(r -> r.run));
    }

    // Appends the new run to the full history, sorts it, and rewrites HIGHSCORE.csv.
    void saveHighScore(long newTimeSeconds) {
        int nextRun = 1;
        for (RunRecord r : runRecords) nextRun = Math.max(nextRun, r.run + 1);

        // Append the new run to the existing history, then re-sort.
        runRecords.add(new RunRecord(nextRun, newTimeSeconds));
        sortRunRecords();
        bestTimeSeconds = runRecords.get(0).seconds;

        // If the old file existed but couldn't be read, never overwrite it: just append one row.
        if (highScoreLoadFailed) {
            try (FileWriter fw = new FileWriter(HIGHSCORE_FILE, true)) {
                fw.write(nextRun + "," + newTimeSeconds + System.lineSeparator());
            } catch (IOException e) {
                System.err.println("Failed to append to " + HIGHSCORE_FILE + ": " + e.getMessage());
            }
            return;
        }

        // Rewrite the full sorted history to a temp file, then swap it in,
        // so a crash mid-write can't destroy the existing records.
        File tmp = new File(HIGHSCORE_FILE + ".tmp");
        try (PrintWriter pw = new PrintWriter(new FileWriter(tmp))) {
            pw.println("Run,TimeSeconds");
            for (RunRecord r : runRecords) pw.println(r.run + "," + r.seconds);
        } catch (IOException e) {
            System.err.println("Failed to write " + HIGHSCORE_FILE + ": " + e.getMessage());
            return;
        }
        try {
            Files.move(tmp.toPath(), new File(HIGHSCORE_FILE).toPath(),
                       StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            System.err.println("Failed to finalize " + HIGHSCORE_FILE + ": " + e.getMessage());
        }
    }

    void loadMap(int number) {
        currentMap = number;
        ArrayList<String> lines = new ArrayList<>();
        try {
            InputStream is = new FileInputStream(mapPaths[number]);
            BufferedReader br = new BufferedReader(new InputStreamReader(is));
            String line;
            while ((line = br.readLine()) != null) {
                line = line.replace("\uFEFF", "").trim();
                if (!line.isEmpty()) lines.add(line);
            }
            br.close();
        } catch (IOException e) { System.err.println("Map load fail: " + mapPaths[number]); return; }

        mapHeight = lines.size();
        mapWidth = lines.get(0).length();
        map = new int[mapHeight][mapWidth];
        monster = null;
        enemyHomeX = enemyHomeY = -1;
        lastCombatTime = System.currentTimeMillis();

        for (int y = 0; y < mapHeight; y++) {
            String l = lines.get(y);
            for (int x = 0; x < mapWidth; x++) {
                char c = (x < l.length()) ? l.charAt(x) : '0';
                if (c == WALL) map[y][x] = 1;
                else if (c == KEY) map[y][x] = 2;
                else if (c == DOOR) map[y][x] = 3;
                else if (c == CHEST) map[y][x] = 4;
                else if (c == POTION) map[y][x] = 5;
                else if (c == MONSTER) {
                    map[y][x] = 0;
                    boolean bossLevel = (currentMap == mapPaths.length - 1);
                    monster = new Monster(x, y, currentMap, bossLevel);
                    monster.image = bossLevel ? bossImg : monsterTints[currentMap % monsterTints.length];
                    enemyHomeX = x;
                    enemyHomeY = y;
                    enemyMoveRange = bossLevel ? 3 : 2;
                } else map[y][x] = 0;
            }
        }
        findPlayerStart();

        inventory.setHasKey(false);
        combatMsgPlayer = combatMsgEnemy = "";
    }

    void findPlayerStart() {
        for (int y = 0; y < mapHeight; y++)
            for (int x = 0; x < mapWidth; x++)
                if (map[y][x] == 0) { player.x = x; player.y = y; return; }
    }

    void startNewGame() {
        player.reset();
        inventory.reset();
        gameOver = false;
        gameWon = false;
        currentMap = 0;
        elapsedTime = 0;
        startTime = System.currentTimeMillis();
        totalPausedMillis = 0;
        pauseStartTime = 0;
        paused = false;
        lastEnemyMoveTime = System.currentTimeMillis();
        showStats = false;
        playerFrame = 0;
        playerDirection = 0;
        lastAnimationTime = System.currentTimeMillis();
        loadMap(0);
        gameState = GameState.PLAYING;
        menuSelection = 0;
        requestFocusInWindow();
    }

    void restartGame() {
        startNewGame();
    }

    @Override
    public void run() {
        while (true) {
            update();
            repaint();
            try { Thread.sleep(100); } catch (InterruptedException ignored) {}
        }
    }

    void update() {
        if (gameState != GameState.PLAYING) return;
        if (paused) return;
        if (gameOver || gameWon) return;

        long now = System.currentTimeMillis();
        elapsedTime = (now - startTime - totalPausedMillis) / 1000;

        if (up) {
            playerDirection = 2;
            movePlayer(0, -1);
            updatePlayerAnimation();
        } else if (down) {
            playerDirection = 0;
            movePlayer(0, 1);
            updatePlayerAnimation();
        } else if (left) {
            playerDirection = 1;
            movePlayer(-1, 0);
            updatePlayerAnimation();
        } else if (right) {
            playerDirection = 3;
            movePlayer(1, 0);
            updatePlayerAnimation();
        }

        // Normal enemies patrol up to 2 tiles from their starting position.
        // The boss can patrol up to 3 tiles. They move one tile at a time.
        if (monster != null && monster.hp > 0 && now - lastEnemyMoveTime >= ENEMY_MOVE_DELAY) {
            moveEnemy();
            lastEnemyMoveTime = now;
        }

        if (monster != null && monster.hp > 0) {
            int dx = Math.abs(player.x - monster.x);
            int dy = Math.abs(player.y - monster.y);
            boolean adjacent = (dx == 1 && dy == 0) || (dx == 0 && dy == 1);
            if (adjacent && now - lastCombatTime >= COMBAT_DELAY) {
                executeCombatRound();
                lastCombatTime = now;
            }
        }
    }

    // Moves the current enemy one tile at a time while keeping it inside
    // its patrol area. Normal enemies have a 2-tile range; the boss has 3.
    void moveEnemy() {
        if (monster == null || monster.hp <= 0 || map == null) return;

        int playerDistance = Math.abs(player.x - monster.x) + Math.abs(player.y - monster.y);
        if (playerDistance <= 1) return;

        int[][] directions = {
            {1, 0}, {-1, 0}, {0, 1}, {0, -1}
        };

        for (int i = directions.length - 1; i > 0; i--) {
            int j = rand.nextInt(i + 1);
            int[] temp = directions[i];
            directions[i] = directions[j];
            directions[j] = temp;
        }

        for (int[] dir : directions) {
            int nx = monster.x + dir[0];
            int ny = monster.y + dir[1];

            if (nx < 0 || nx >= mapWidth || ny < 0 || ny >= mapHeight) continue;

            if (Math.abs(nx - enemyHomeX) > enemyMoveRange ||
                Math.abs(ny - enemyHomeY) > enemyMoveRange) continue;

            // 0 is walkable floor. Do not let enemies occupy special objects
            // or the player's current tile.
            if (map[ny][nx] != 0) continue;
            if (nx == player.x && ny == player.y) continue;

            monster.x = nx;
            monster.y = ny;
            return;
        }
    }

    void executeCombatRound() {
        boolean isCrit = rand.nextFloat() < 0.40f;
        int pDmg = (int) ((Entity.CUST_DMG + rand.nextInt(11)) * inventory.getDmgBoost() * (isCrit ? 2.0 : 1.0));
        monster.hp = Math.max(0, monster.hp - pDmg);
        combatMsgPlayer = "You hit Monster for " + pDmg + (isCrit ? " (CRIT!)" : "");

        if (monster.hp == 0) { combatMsgEnemy = monster.isBoss ? "BOSS DEFEATED!" : "Monster Defeated!"; return; }

        int mDmg = monster.isBoss ? (13 + rand.nextInt(14)) : (6 + rand.nextInt(10));
        player.hp = Math.max(0, player.hp - mDmg);
        combatMsgEnemy = "Monster dealt " + mDmg + " damage!";
        if (player.hp == 0) gameOver = true;
    }

    void movePlayer(int dx, int dy) {
        int nx = player.x + dx, ny = player.y + dy;
        if (nx < 0 || nx >= mapWidth || ny < 0 || ny >= mapHeight) return;

        int tile = map[ny][nx];
        if (tile == 1 || (monster != null && nx == monster.x && ny == monster.y && monster.hp > 0)) return;

        if (tile == 2) { inventory.collectKey(); map[ny][nx] = 0; }
        else if (tile == 3) {
            if (!inventory.hasKey()) return;
            if (monster != null && monster.hp > 0) {
                combatMsgPlayer = monster.isBoss ? "Defeat the boss first!" : "Something blocks the way!";
                return;
            }
            if (currentMap == mapPaths.length - 1) {
                gameWon = true;
                saveHighScore(elapsedTime); // every successful run is recorded, not just new bests
                return;
            }
            loadMap(currentMap + 1);
            return;
        }
        else if (tile == 4) { combatMsgPlayer = inventory.collectChest(); map[ny][nx] = 0; }
        else if (tile == 5) { combatMsgPlayer = inventory.collectPotion(player); map[ny][nx] = 0; }

        player.x = nx; player.y = ny;
    }

    void drawMainMenu(Graphics g) {
        g.setColor(new Color(12, 12, 18));
        g.fillRect(0, 0, SCREEN_WIDTH, SCREEN_HEIGHT);

        g.setColor(new Color(220, 40, 40));
        g.setFont(new Font("Arial", Font.BOLD, 52));
        String title = "DUNGEON EXPLORER";
        int titleWidth = g.getFontMetrics().stringWidth(title);
        g.drawString(title, (SCREEN_WIDTH - titleWidth) / 2, 145);

        g.setColor(Color.LIGHT_GRAY);
        g.setFont(new Font("Arial", Font.PLAIN, 18));
        String subtitle = "A Core Java Dungeon RPG";
        int subtitleWidth = g.getFontMetrics().stringWidth(subtitle);
        g.drawString(subtitle, (SCREEN_WIDTH - subtitleWidth) / 2, 180);

        String[] options = { "NEW GAME", "HOW TO PLAY", "EXIT" };
        int startY = 270;

        for (int i = 0; i < options.length; i++) {
            int y = startY + i * 70;
            boolean selected = (i == menuSelection);

            g.setColor(selected ? new Color(80, 80, 95) : new Color(40, 40, 50));
            g.fillRoundRect(260, y, 248, 50, 15, 15);
            g.setColor(selected ? Color.YELLOW : Color.WHITE);
            g.drawRoundRect(260, y, 248, 50, 15, 15);

            g.setFont(new Font("Arial", Font.BOLD, 18));
            int textWidth = g.getFontMetrics().stringWidth(options[i]);
            g.drawString(options[i], 260 + (248 - textWidth) / 2, y + 32);
        }

        g.setColor(Color.GRAY);
        g.setFont(new Font("Arial", Font.PLAIN, 14));
        String controls = "UP / DOWN = Select     ENTER = Confirm";
        int controlsWidth = g.getFontMetrics().stringWidth(controls);
        g.drawString(controls, (SCREEN_WIDTH - controlsWidth) / 2, 510);

        if (bestTimeSeconds != -1) {
            g.setColor(Color.YELLOW);
            String best = "Best Time: " + bestTimeSeconds + " seconds";
            int bestWidth = g.getFontMetrics().stringWidth(best);
            g.drawString(best, (SCREEN_WIDTH - bestWidth) / 2, 550);
        }
    }

    void drawInstructions(Graphics g) {
        g.setColor(new Color(12, 12, 18));
        g.fillRect(0, 0, SCREEN_WIDTH, SCREEN_HEIGHT);

        g.setColor(Color.RED);
        g.setFont(new Font("Arial", Font.BOLD, 40));
        String title = "HOW TO PLAY";
        int titleWidth = g.getFontMetrics().stringWidth(title);
        g.drawString(title, (SCREEN_WIDTH - titleWidth) / 2, 110);

        g.setColor(Color.WHITE);
        g.setFont(new Font("Arial", Font.PLAIN, 18));
        String[] instructions = {
            "W / A / S / D or Arrow Keys - Move",
            "Collect the key to unlock the door",
            "Collect potions to restore HP",
            "Collect chests to increase damage",
            "Defeat enemies to progress",
            "Defeat the boss in the final room",
            "Press TAB during gameplay to view Game Stats",
            "Press P to Pause / Resume the game",
            "Press ESC to return to the Main Menu"
        };

        int y = 190;
        for (String text : instructions) {
            int width = g.getFontMetrics().stringWidth(text);
            g.drawString(text, (SCREEN_WIDTH - width) / 2, y);
            y += 40;
        }

        g.setColor(Color.YELLOW);
        g.setFont(new Font("Arial", Font.BOLD, 16));
        String back = "Press ESC to return";
        int backWidth = g.getFontMetrics().stringWidth(back);
        g.drawString(back, (SCREEN_WIDTH - backWidth) / 2, 545);
    }

    void drawStatsPanel(Graphics g) {
        int panelX = 190, panelY = 115, panelW = 390, panelH = 445;

        g.setColor(new Color(0, 0, 0, 235));
        g.fillRoundRect(panelX, panelY, panelW, panelH, 20, 20);

        g.setColor(Color.YELLOW);
        g.setFont(new Font("Arial", Font.BOLD, 30));
        String title = "GAME STATS";
        int titleWidth = g.getFontMetrics().stringWidth(title);
        g.drawString(title, panelX + (panelW - titleWidth) / 2, panelY + 50);

        g.setFont(new Font("Arial", Font.PLAIN, 18));
        int y = panelY + 100;
        g.setColor(Color.WHITE);
        g.drawString("Health: " + player.hp + " / " + player.maxHP, panelX + 45, y);
        y += 38;
        g.drawString("Current Map: " + (currentMap + 1) + " / " + mapPaths.length, panelX + 45, y);
        y += 38;
        g.drawString("Key: " + (inventory.hasKey() ? "Collected" : "Not Collected"), panelX + 45, y);
        y += 38;
        g.drawString("Damage Boost: +" + inventory.getDmgBoostPercent() + "%", panelX + 45, y);
        y += 38;
        g.drawString("Time: " + elapsedTime + " seconds", panelX + 45, y);
        y += 38;
        String best = bestTimeSeconds == -1 ? "No record yet" : bestTimeSeconds + " seconds";
        g.drawString("Best Time: " + best, panelX + 45, y);
        y += 38;

        String enemyStatus;
        if (monster == null) enemyStatus = "None";
        else if (monster.hp <= 0) enemyStatus = monster.isBoss ? "Boss Defeated" : "Enemy Defeated";
        else enemyStatus = monster.isBoss ? "Boss Active" : "Enemy Active";
        g.drawString("Enemy Status: " + enemyStatus, panelX + 45, y);

        y += 55;
        g.setColor(Color.LIGHT_GRAY);
        g.setFont(new Font("Arial", Font.PLAIN, 14));
        String hint = "Press TAB to close";
        int hintWidth = g.getFontMetrics().stringWidth(hint);
        g.drawString(hint, panelX + (panelW - hintWidth) / 2, y);
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);

        // Clear the actual component first.
        g.setColor(Color.BLACK);
        g.fillRect(0, 0, getWidth(), getHeight());

        // Scale the fixed 768x676 game canvas to fit the current window/screen.
        double scaleX = (double) getWidth() / SCREEN_WIDTH;
        double scaleY = (double) getHeight() / SCREEN_HEIGHT;
        double scale = Math.min(scaleX, scaleY);

        int drawWidth = (int) Math.round(SCREEN_WIDTH * scale);
        int drawHeight = (int) Math.round(SCREEN_HEIGHT * scale);
        int offsetX = (getWidth() - drawWidth) / 2;
        int offsetY = (getHeight() - drawHeight) / 2;

        Graphics2D g2 = (Graphics2D) g.create();
        g2.translate(offsetX, offsetY);
        g2.scale(scale, scale);
        g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_SPEED);

        drawGame(g2);
        g2.dispose();
    }

    // All gameplay/menu drawing uses the fixed virtual resolution.
    void drawGame(Graphics g) {
        if (gameState == GameState.MENU) {
            drawMainMenu(g);
            drawDisplayModeHint(g);
            return;
        }

        if (gameState == GameState.INSTRUCTIONS) {
            drawInstructions(g);
            drawDisplayModeHint(g);
            return;
        }

        g.setColor(new Color(15, 15, 15)); g.fillRect(0, 0, SCREEN_WIDTH, SCREEN_HEIGHT);
        g.setColor(new Color(25, 25, 25)); g.fillRect(0, 0, SCREEN_WIDTH, HUD_HEIGHT);
        g.setColor(Color.DARK_GRAY); g.drawLine(0, HUD_HEIGHT, SCREEN_WIDTH, HUD_HEIGHT);

        if (map == null) return;

        for (int y = 0; y < mapHeight; y++) {
            for (int x = 0; x < mapWidth; x++) {
                int tile = map[y][x], sx = x * TILE_SIZE, sy = y * TILE_SIZE + HUD_HEIGHT;
                g.setColor(new Color(45, 45, 45)); g.fillRect(sx, sy, TILE_SIZE, TILE_SIZE);
                if (tile == 1 && wallImg != null) g.drawImage(wallImg, sx, sy, TILE_SIZE, TILE_SIZE, null);
                else if (tile == 2 && keyImg != null) g.drawImage(keyImg, sx, sy, TILE_SIZE, TILE_SIZE, null);
                else if (tile == 3 && doorImg != null) g.drawImage(doorImg, sx, sy, TILE_SIZE, TILE_SIZE, null);
                else if (tile == 4 && chestImg != null) g.drawImage(chestImg, sx, sy, TILE_SIZE, TILE_SIZE, null);
                else if (tile == 5 && potionImg != null) g.drawImage(potionImg, sx, sy, TILE_SIZE, TILE_SIZE, null);
                g.setColor(new Color(35, 35, 35)); g.drawRect(sx, sy, TILE_SIZE, TILE_SIZE);
            }
        }

        if (monster != null && monster.hp > 0) {
            int mx = monster.x * TILE_SIZE, my = monster.y * TILE_SIZE + HUD_HEIGHT;
            if (monster.isBoss) {
                int bossSize = (int) (TILE_SIZE * 1.5);
                int off = (bossSize - TILE_SIZE) / 2;
                if (monster.image != null) g.drawImage(monster.image, mx - off, my - off, bossSize, bossSize, null);
                else { g.setColor(new Color(160, 0, 0)); g.fillRect(mx - off + 8, my - off + 8, bossSize - 16, bossSize - 16); }
                g.setFont(new Font("Arial", Font.BOLD, 12));
                g.setColor(Color.RED);
                g.drawString("BOSS", mx - 6, my - off - 4);
            } else {
                if (monster.image != null) g.drawImage(monster.image, mx, my, TILE_SIZE, TILE_SIZE, null);
                else { g.setColor(Color.RED); g.fillRect(mx + 8, my + 8, TILE_SIZE - 16, TILE_SIZE - 16); }
            }

            g.setColor(Color.BLACK); g.fillRect(mx, my - 8, TILE_SIZE, 5);
            g.setColor(monster.isBoss ? new Color(190, 0, 190) : Color.GREEN);
            g.fillRect(mx, my - 8, (int) (((double) monster.hp / monster.maxHP) * TILE_SIZE), 5);
        }

        int px = player.x * TILE_SIZE, py = player.y * TILE_SIZE + HUD_HEIGHT;
        drawPlayerSprite(g, px, py, TILE_SIZE, TILE_SIZE);

        // --- TOP-LEFT HUD SECTION ---
        drawPlayerSprite(g, 15, 15, 48, 48);

        g.setColor(Color.RED); g.fillRect(75, 15, 160, 16);
        g.setColor(Color.GREEN); g.fillRect(75, 15, (int) (((double) player.hp / player.maxHP) * 160), 16);
        g.setColor(Color.WHITE); g.drawRect(75, 15, 160, 16);
        g.setFont(new Font("Arial", Font.BOLD, 11));
        g.drawString(player.hp + "/" + player.maxHP + " HP", 125, 27);

        g.setFont(new Font("Arial", Font.BOLD, 12));
        g.setColor(Color.CYAN);
        g.drawString("TIME: " + elapsedTime + "s", 75, 46);
        String bestStr = (bestTimeSeconds == -1) ? "BEST: --" : "BEST: " + bestTimeSeconds + "s";
        g.setColor(Color.YELLOW);
        g.drawString(bestStr, 155, 46);

        g.setFont(new Font("Arial", Font.PLAIN, 12));
        g.setColor(Color.WHITE);
        g.drawString("Map: " + (currentMap + 1) + "  |  Key: " + (inventory.hasKey() ? "YES" : "NO"), 75, 64);
        g.drawString("Dmg Boost: +" + inventory.getDmgBoostPercent() + "%", 75, 80);

        if (!combatMsgPlayer.isEmpty()) {
            g.setFont(new Font("Arial", Font.BOLD, 14));
            g.setColor(Color.YELLOW); g.drawString(combatMsgPlayer, 280, 35);
            g.setColor(Color.ORANGE); g.drawString(combatMsgEnemy, 280, 60);
        }

        if (gameOver) {
            g.setColor(new Color(0, 0, 0, 220)); g.fillRect(0, 0, SCREEN_WIDTH, SCREEN_HEIGHT);
            g.setColor(Color.RED); g.setFont(new Font("Arial", Font.BOLD, 42));
            g.drawString("YOU DIED", (SCREEN_WIDTH / 2) - 100, (SCREEN_HEIGHT / 2) - 30);
            g.setColor(Color.WHITE); g.setFont(new Font("Arial", Font.PLAIN, 18));
            g.drawString("Press ENTER or SPACE to Respawn", (SCREEN_WIDTH / 2) - 150, (SCREEN_HEIGHT / 2) + 30);
            g.drawString("Press ESC for Main Menu", (SCREEN_WIDTH / 2) - 105, (SCREEN_HEIGHT / 2) + 65);
        } else if (gameWon) {
            g.setColor(new Color(0, 0, 0, 220)); g.fillRect(0, 0, SCREEN_WIDTH, SCREEN_HEIGHT);
            g.setColor(Color.GREEN); g.setFont(new Font("Arial", Font.BOLD, 42));
            g.drawString("YOU WON!", (SCREEN_WIDTH / 2) - 110, (SCREEN_HEIGHT / 2) - 60);
            g.setColor(Color.YELLOW); g.setFont(new Font("Arial", Font.BOLD, 20));
            g.drawString("CONGRATULATIONS!", (SCREEN_WIDTH / 2) - 115, (SCREEN_HEIGHT / 2) - 10);
            g.setColor(Color.CYAN); g.setFont(new Font("Arial", Font.PLAIN, 18));
            g.drawString("Your Time: " + elapsedTime + " seconds", (SCREEN_WIDTH / 2) - 100, (SCREEN_HEIGHT / 2) + 25);
            g.drawString("Best Record: " + bestTimeSeconds + " seconds", (SCREEN_WIDTH / 2) - 105, (SCREEN_HEIGHT / 2) + 55);
            g.setColor(Color.WHITE); g.setFont(new Font("Arial", Font.PLAIN, 16));
            g.drawString("Press ENTER or SPACE to Play Again", (SCREEN_WIDTH / 2) - 145, (SCREEN_HEIGHT / 2) + 95);
            g.drawString("Press ESC for Main Menu", (SCREEN_WIDTH / 2) - 105, (SCREEN_HEIGHT / 2) + 125);
        }

        if (showStats) drawStatsPanel(g);

        if (paused) drawPauseOverlay(g);

        drawDisplayModeHint(g);
    }

    void drawPauseOverlay(Graphics g) {
        g.setColor(new Color(0, 0, 0, 190));
        g.fillRect(0, 0, SCREEN_WIDTH, SCREEN_HEIGHT);

        g.setColor(Color.YELLOW);
        g.setFont(new Font("Arial", Font.BOLD, 48));
        String title = "GAME PAUSED";
        int titleWidth = g.getFontMetrics().stringWidth(title);
        g.drawString(title, (SCREEN_WIDTH - titleWidth) / 2, 275);

        g.setColor(Color.WHITE);
        g.setFont(new Font("Arial", Font.PLAIN, 20));
        String text = "Press P to Resume";
        int textWidth = g.getFontMetrics().stringWidth(text);
        g.drawString(text, (SCREEN_WIDTH - textWidth) / 2, 325);

        g.setFont(new Font("Arial", Font.PLAIN, 16));
        String menuText = "Press ESC to return to Main Menu";
        int menuWidth = g.getFontMetrics().stringWidth(menuText);
        g.drawString(menuText, (SCREEN_WIDTH - menuWidth) / 2, 360);
    }

    void drawDisplayModeHint(Graphics g) {
        g.setColor(new Color(190, 190, 190));
        g.setFont(new Font("Arial", Font.PLAIN, 12));
        String text = fullscreen ? "F11 = Windowed Mode" : "F11 = Full Screen";
        int width = g.getFontMetrics().stringWidth(text);
        g.drawString(text, SCREEN_WIDTH - width - 12, SCREEN_HEIGHT - 10);
    }

    void toggleFullscreen() {
        if (window == null) return;

        GraphicsDevice device = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice();

        if (!fullscreen) {
            // Remember the user's current window before entering fullscreen.
            windowedBounds = window.getBounds();
            windowedExtendedState = window.getExtendedState();

            fullscreen = true;
            window.dispose();
            window.setUndecorated(true);
            window.setResizable(false);
            device.setFullScreenWindow(window);
            window.setVisible(true);
            requestFocusInWindow();
        } else {
            // Leave fullscreen and restore the previous windowed size/position.
            device.setFullScreenWindow(null);
            window.dispose();
            window.setUndecorated(false);
            window.setResizable(true);

            if (windowedBounds != null) window.setBounds(windowedBounds);
            else {
                window.pack();
                window.setLocationRelativeTo(null);
            }

            window.setExtendedState(windowedExtendedState);
            window.setVisible(true);
            fullscreen = false;
            requestFocusInWindow();
        }

        repaint();
    }

    void togglePause() {
        if (gameState != GameState.PLAYING || gameOver || gameWon) return;

        long now = System.currentTimeMillis();

        if (!paused) {
            paused = true;
            pauseStartTime = now;
            up = down = left = right = false;
        } else {
            totalPausedMillis += now - pauseStartTime;
            pauseStartTime = 0;
            paused = false;
            lastEnemyMoveTime = now;
            lastAnimationTime = now;
            lastCombatTime = now;
        }

        requestFocusInWindow();
        repaint();
    }

    @Override
    public void keyPressed(KeyEvent e) {
        int k = e.getKeyCode();

        // F11 toggles fullscreen from any game screen.
        if (k == KeyEvent.VK_F11) {
            toggleFullscreen();
            return;
        }

        if (gameState == GameState.MENU) {
            if (k == KeyEvent.VK_UP || k == KeyEvent.VK_W) {
                menuSelection--;
                if (menuSelection < 0) menuSelection = 2;
                return;
            }
            if (k == KeyEvent.VK_DOWN || k == KeyEvent.VK_S) {
                menuSelection++;
                if (menuSelection > 2) menuSelection = 0;
                return;
            }
            if (k == KeyEvent.VK_ENTER || k == KeyEvent.VK_SPACE) {
                if (menuSelection == 0) startNewGame();
                else if (menuSelection == 1) gameState = GameState.INSTRUCTIONS;
                else System.exit(0);
                return;
            }
            return;
        }

        if (gameState == GameState.INSTRUCTIONS) {
            if (k == KeyEvent.VK_ESCAPE) {
                gameState = GameState.MENU;
                requestFocusInWindow();
            }
            return;
        }

        if (k == KeyEvent.VK_P) {
            togglePause();
            return;
        }

        if (k == KeyEvent.VK_TAB) {
            if (!paused) showStats = !showStats;
            return;
        }

        if (k == KeyEvent.VK_ESCAPE) {
            showStats = false;
            paused = false;
            pauseStartTime = 0;
            gameState = GameState.MENU;
            up = down = left = right = false;
            requestFocusInWindow();
            return;
        }

        if ((gameOver || gameWon) && (k == KeyEvent.VK_ENTER || k == KeyEvent.VK_SPACE)) {
            restartGame();
            return;
        }

        if (gameOver || gameWon) return;

        if (k == KeyEvent.VK_W || k == KeyEvent.VK_UP) up = true;
        if (k == KeyEvent.VK_S || k == KeyEvent.VK_DOWN) down = true;
        if (k == KeyEvent.VK_A || k == KeyEvent.VK_LEFT) left = true;
        if (k == KeyEvent.VK_D || k == KeyEvent.VK_RIGHT) right = true;
    }

    @Override
    public void keyReleased(KeyEvent e) {
        int k = e.getKeyCode();
        if (k == KeyEvent.VK_W || k == KeyEvent.VK_UP) up = false;
        if (k == KeyEvent.VK_S || k == KeyEvent.VK_DOWN) down = false;
        if (k == KeyEvent.VK_A || k == KeyEvent.VK_LEFT) left = false;
        if (k == KeyEvent.VK_D || k == KeyEvent.VK_RIGHT) right = false;
    }

    @Override public void keyTyped(KeyEvent e) {}

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            JFrame window = new JFrame("Dungeon Explorer");
            Game game = new Game();

            game.window = window;
            window.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            window.setResizable(true);
            window.add(game);
            window.pack();
            window.setMinimumSize(new Dimension(SCREEN_WIDTH / 2, SCREEN_HEIGHT / 2));
            window.setLocationRelativeTo(null);
            window.setVisible(true);
            game.requestFocusInWindow();

            new Thread(game, "DungeonGameLoop").start();
        });
    }
}