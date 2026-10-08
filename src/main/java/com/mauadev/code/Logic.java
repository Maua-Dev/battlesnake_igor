package com.mauadev.code;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

import com.mauadev.code.entities.Coordinate;
import com.mauadev.code.entities.GameState;
import com.mauadev.code.entities.Snake;

/**
 * Bot de Battlesnake baseado em busca adversarial.
 *
 * Ideia geral:
 *  1. O estado do jogo e convertido em um modelo interno que simula as regras
 *     de movimento, rabo que sai, comida, hazard, colisoes e head-to-head.
 *  2. Uma busca com aprofundamento iterativo (minimax com movimentos simultaneos
 *     e poda alpha-beta) escolhe o movimento que da o melhor resultado no pior
 *     caso. Os dois inimigos mais proximos e todas as ameacas imediatas sao
 *     ramificados. Uma tabela local reutiliza apenas a ordem dos movimentos.
 *  3. Nas folhas, o estado e avaliado por territorio (Voronoi), camaras livres,
 *     tamanho relativo, fome e comida. Uma verificacao temporal limitada da
 *     raiz complementa as estimativas de espaco sem bloquear vitorias imediatas.
 *
 * Nao ha estado estatico mutavel: varias partidas podem ser jogadas em paralelo.
 * Comida futura e expansao aleatoria de hazards nao sao previstas na busca.
 */
public class Logic {

    // =========================================================
    // CONFIGURACAO
    // =========================================================

    /** Limite configuravel, sempre reduzido para caber no timeout da partida. */
    private static final long DEFAULT_BUDGET_MS = 300L;

    private static final int MAX_DEPTH = 24;

    /** Quantos inimigos (os mais proximos) sao ramificados por completo na busca. */
    private static final int FULL_BRANCH_OPPONENTS = 2;

    /** Dano extra por turno em casa de hazard (ruleset royale padrao). */
    private static final int HAZARD_DAMAGE = 14;

    private static final int INF = 1_000_000;
    private static final int WIN = 100_000;
    private static final int LOSS = -100_000;
    private static final int DRAW = -30_000;
    private static final int UNREACHABLE = 10_000;

    private static final String[] NAMES = {"up", "down", "left", "right"};
    private static final int[] DX = {0, 0, -1, 1};
    private static final int[] DY = {1, -1, 0, 0};

    // =========================================================
    // INFORMACOES
    // =========================================================

    public static Map<String, String> info() {
        Map<String, String> info = new HashMap<>();

        info.put("apiversion", "1");
        info.put("author", "");
        info.put("color", "#32CD32");
        info.put("head", "replit-mark");
        info.put("tail", "mlh-gene");

        return info;
    }

    public static void start(GameState state) {
    }

    public static void end(GameState state) {
    }

    // =========================================================
    // DECISAO PRINCIPAL
    // =========================================================

    public static String getMove(GameState state) {

        long started = System.nanoTime();
        long deadline = started + searchBudgetNanos(state);
        World world = null;

        try {
            world = buildWorld(state);

            if (world == null) {
                return "up";
            }

            Search search = new Search(world, deadline);
            int move = search.run();
            if (Boolean.getBoolean("snake.debug")) {
                // Diagnostico opt-in: nao altera o JSON nem compartilha estado.
                System.err.println("snake move=" + NAMES[move]
                    + " turn=" + state.getTurn()
                    + " depth=" + search.completedDepth + " nodes=" + search.nodes
                    + " elapsedUs=" + (System.nanoTime() - started) / 1_000L
                    + " fallback=" + search.usedFallback + " timeout=" + search.timeUp
                    + " escapeNodes=" + search.escapeNodes
                    + " trapPenalty=" + search.rootSafetyPenalty[move]);
            }
            return NAMES[move];

        } catch (RuntimeException e) {

            if (Boolean.getBoolean("snake.debug")) {
                System.err.println("snake fallback=exception type=" + e.getClass().getSimpleName());
            }

            // Se a busca falhar, usa a mesma avaliacao de emergencia.
            try {
                if (world != null) {
                    Search s = new Search(world, System.nanoTime());
                    return NAMES[s.fallbackMove(world)];
                }
            } catch (RuntimeException ignored) {
                // cai no retorno padrao
            }

            return "up";
        }
    }

    static long searchBudgetNanos(GameState state) {
        int timeout = state != null && state.getGame() != null
            ? state.getGame().getTimeout() : 500;
        if (timeout <= 0) {
            timeout = 500;
        }
        // Reserva para desserializacao, resposta HTTP e latencia de rede.
        long margin = Math.max(25L, timeout / 5L);
        long configured = Math.max(0L, Long.getLong("snake.budget.ms", DEFAULT_BUDGET_MS));
        return Math.min(configured, Math.max(0L, timeout - margin)) * 1_000_000L;
    }

    // =========================================================
    // GEOMETRIA DO TABULEIRO
    // =========================================================

    /** Tabela de vizinhos pre-calculada. Celula = y * largura + x. */
    static final class Geo {

        final int w;
        final int h;
        final int cells;
        final int[][] nbr;

        Geo(int w, int h) {
            this.w = w;
            this.h = h;
            this.cells = w * h;
            this.nbr = new int[cells][4];

            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    for (int d = 0; d < 4; d++) {

                        int nx = x + DX[d];
                        int ny = y + DY[d];

                        nbr[y * w + x][d] =
                            (nx >= 0 && ny >= 0 && nx < w && ny < h)
                                ? ny * w + nx
                                : -1;
                    }
                }
            }
        }

        int manhattan(int a, int b) {
            int dx = Math.abs(a % w - b % w);
            int dy = Math.abs(a / w - b / w);
            return dx + dy;
        }
    }

    // =========================================================
    // MODELO INTERNO DO JOGO
    // =========================================================

    /** Cobra imutavel. body[0] e a cabeca. Segmentos empilhados aparecem repetidos. */
    static final class Snk {

        final int[] body;
        final int health;
        final boolean alive;

        Snk(int[] body, int health, boolean alive) {
            this.body = body;
            this.health = health;
            this.alive = alive;
        }
    }

    /** Estado imutavel. snakes[0] e sempre a nossa cobra. */
    static final class World {

        final Geo geo;
        final int[] hazard;
        final boolean[] food;
        final Snk[] snakes;

        World(Geo geo, int[] hazard, boolean[] food, Snk[] snakes) {
            this.geo = geo;
            this.hazard = hazard;
            this.food = food;
            this.snakes = snakes;
        }
    }

    static World buildWorld(GameState state) {

        if (state == null || state.getBoard() == null || state.getYou() == null) {
            return null;
        }

        int w = state.getBoard().getWidth();
        int h = state.getBoard().getHeight();

        if (w <= 0 || h <= 0 || (long) w * h > 100_000) {
            return null;
        }
        Geo geo = new Geo(w, h);

        Snake you = state.getYou();

        Snk me = toSnk(geo, you);

        if (me == null) {
            return null;
        }

        List<Snk> all = new ArrayList<>();
        all.add(me);

        List<Snake> boardSnakes = state.getBoard().getSnakes();

        if (boardSnakes != null) {

            for (Snake other : boardSnakes) {

                if (isSameSnake(you, other)) {
                    continue;
                }

                Snk snk = toSnk(geo, other);

                if (snk != null) {
                    all.add(snk);
                }
            }
        }

        boolean[] food = new boolean[geo.cells];
        int[] hazard = new int[geo.cells];

        mark(geo, state.getBoard().getFood(), food);
        List<Coordinate> hazards = state.getBoard().getHazards();
        if (hazards != null) {
            for (Coordinate c : hazards) {
                if (valid(geo, c)) {
                    hazard[c.getY() * w + c.getX()]++;
                }
            }
        }

        return new World(geo, hazard, food, all.toArray(new Snk[0]));
    }

    private static boolean isSameSnake(Snake you, Snake other) {

        if (other == null || you == other) {
            return true;
        }

        if (you.getId() != null && you.getId().equals(other.getId())) {
            return true;
        }

        if (you.getId() == null && other.getId() == null
            && you.getBody() != null && other.getBody() != null
            && !you.getBody().isEmpty() && !other.getBody().isEmpty()) {

            Coordinate a = you.getBody().get(0);
            Coordinate b = other.getBody().get(0);

            return a != null && b != null && a.getX() == b.getX() && a.getY() == b.getY();
        }

        return false;
    }

    private static Snk toSnk(Geo geo, Snake snake) {

        if (snake == null || snake.getHealth() <= 0) {
            return null;
        }

        List<Coordinate> body = snake.getBody();

        if (body == null || body.isEmpty()) {
            return null;
        }

        int[] cells = new int[body.size()];

        for (int i = 0; i < cells.length; i++) {

            Coordinate c = body.get(i);

            if (!valid(geo, c)) {
                return null;
            }

            cells[i] = c.getY() * geo.w + c.getX();
        }

        return new Snk(cells, snake.getHealth(), true);
    }

    private static void mark(Geo geo, List<Coordinate> list, boolean[] target) {

        if (list == null) {
            return;
        }

        for (Coordinate c : list) {

            if (valid(geo, c)) {

                target[c.getY() * geo.w + c.getX()] = true;
            }
        }
    }

    private static boolean valid(Geo geo, Coordinate c) {
        return c != null && c.getX() >= 0 && c.getY() >= 0
            && c.getX() < geo.w && c.getY() < geo.h;
    }

    // =========================================================
    // BUSCA
    // =========================================================

    static final class Search {

        final Geo geo;
        final World root;
        final long deadline;
        final boolean hadOpponents;

        // Buffers reutilizaveis (a busca e single-thread).
        final int[] release;
        final int[][] dist;
        final int[] queue;
        final boolean[] seen;
        final boolean[] headThreat;
        final int[] foodCost;
        final int[] foodTurns;
        final PriorityQueue<Path> paths = new PriorityQueue<>();

        // Tabela por jogada: guarda somente uma sugestao de movimento.
        // Reutiliza apenas a ordem; valores e podas sao sempre calculados novamente.
        private static final int ORDER_TABLE_SIZE = 8192;
        final boolean useOrderTable;
        final long[] orderKeys;
        final int[] orderHints;

        boolean timeUp;
        long nodes;
        int completedDepth;
        boolean usedFallback;
        int largestChamber;
        long escapeNodes;
        int probeVisited;
        long probeDeadline;
        private static final int ROOT_TRAP_PENALTY = 3_000;
        private static final int ESCAPE_NODE_LIMIT = 256;
        final int[] rootSafetyPenalty = new int[4];

        int[] rootOrder;
        int rootBestMove;

        Search(World root, long deadline) {
            this(root, deadline, true);
        }

        Search(World root, long deadline, boolean useOrderTable) {
            this.geo = root.geo;
            this.root = root;
            this.deadline = deadline;
            this.hadOpponents = root.snakes.length > 1;
            this.useOrderTable = useOrderTable;
            this.orderKeys = new long[useOrderTable ? ORDER_TABLE_SIZE : 0];
            this.orderHints = new int[useOrderTable ? ORDER_TABLE_SIZE : 0];
            Arrays.fill(orderHints, -1);

            this.release = new int[geo.cells];
            this.dist = new int[root.snakes.length][geo.cells];
            this.queue = new int[geo.cells];
            this.seen = new boolean[geo.cells];
            this.headThreat = new boolean[geo.cells];
            this.foodCost = new int[geo.cells];
            this.foodTurns = new int[geo.cells];
        }

        // -----------------------------------------------------
        // Aprofundamento iterativo
        // -----------------------------------------------------

        int run() {

            int[] candidates = legalMoves(root, 0, computeRelease(root));

            if (candidates.length == 1) {
                return candidates[0];
            }

            rootOrder = orderMoves(root, 0, candidates, computeRelease(root));
            prepareEscapeChecks(candidates);

            // Avalia todos os candidatos antes da busca: timeout nao vira "up".
            int best = fallbackMove(root);
            usedFallback = true;
            moveToFront(best);

            for (int depth = 1; depth <= MAX_DEPTH; depth++) {

                rootBestMove = -1;

                int value = value(root, depth, -INF, INF, true);

                if (timeUp) {
                    break;
                }
                completedDepth = depth;

                if (rootBestMove >= 0) {
                    best = rootBestMove;
                    usedFallback = false;
                    moveToFront(best);
                }

                // Vitoria forcada encontrada: nao ha o que melhorar.
                if (value >= WIN - ROOT_TRAP_PENALTY) {
                    break;
                }
            }

            return best;
        }

        /**
         * Prova limitada de beco criado pelo proprio corpo. Ignora inimigos e
         * vida, portanto nao confunde fome futura ou uma possivel morte inimiga
         * com aprisionamento. Uma interrupcao e inconclusiva, nunca "morte".
         * Usa no maximo 10% do tempo restante; a busca adversarial vem depois.
         */
        void prepareEscapeChecks(int[] candidates) {
            long now = System.nanoTime();
            probeDeadline = now + Math.max(0L, (deadline - now) / 10L);
            for (int move : candidates) {
                if (System.nanoTime() >= probeDeadline) {
                    break;
                }
                Snk me = root.snakes[0];
                int next = geo.nbr[me.body[0]][move];
                if (next < 0) {
                    continue;
                }
                boolean[] food = root.food.clone();
                int[] body = advanceOwnBody(me.body, next, food[next]);
                if (body == null) {
                    continue;
                }
                food[next] = false;
                probeVisited = 0;
                int horizon = Math.min(24, body.length + 2);
                if (escapePath(body, food, horizon - 1) < 0) {
                    rootSafetyPenalty[move] = ROOT_TRAP_PENALTY;
                }
            }
        }

        /** 1 = continuacao encontrada, 0 = inconclusivo, -1 = todas esgotadas. */
        int escapePath(int[] body, boolean[] food, int remaining) {
            if (remaining == 0) {
                return 1;
            }
            if (probeVisited >= ESCAPE_NODE_LIMIT
                || (probeVisited % 16 == 0 && System.nanoTime() >= probeDeadline)) {
                return 0;
            }
            probeVisited++;
            escapeNodes++;
            boolean unknown = false;
            for (int next : geo.nbr[body[0]]) {
                if (next < 0) {
                    continue;
                }
                boolean ate = food[next];
                int[] moved = advanceOwnBody(body, next, ate);
                if (moved == null) {
                    continue;
                }
                if (ate) {
                    food[next] = false;
                }
                int result = escapePath(moved, food, remaining - 1);
                if (ate) {
                    food[next] = true;
                }
                if (result > 0) {
                    return 1;
                }
                unknown |= result == 0;
            }
            return unknown ? 0 : -1;
        }

        int[] advanceOwnBody(int[] body, int next, boolean ate) {
            for (int i = 0; i < body.length - 1; i++) {
                if (body[i] == next) {
                    return null;
                }
            }
            int[] moved = new int[body.length + (ate ? 1 : 0)];
            moved[0] = next;
            System.arraycopy(body, 0, moved, 1, body.length - 1);
            if (ate) {
                moved[body.length] = moved[body.length - 1];
            }
            return moved;
        }

        void moveToFront(int move) {
            promote(rootOrder, move);
        }

        void promote(int[] moves, int preferred) {
            for (int i = 1; i < moves.length; i++) {
                if (moves[i] == preferred) {
                    System.arraycopy(moves, 0, moves, 1, i);
                    moves[0] = preferred;
                    return;
                }
            }
        }

        /** Chave da posicao: inclui vida, ordem do corpo, cobras vivas e comida. */
        long positionKey(World s) {
            long key = 0xcbf29ce484222325L;
            for (int i = 0; i < s.snakes.length; i++) {
                Snk snake = s.snakes[i];
                key = (key ^ (i + 1L)) * 0x100000001b3L;
                key = (key ^ (snake.alive ? 1L : 0L)) * 0x100000001b3L;
                if (!snake.alive) {
                    continue;
                }
                key = (key ^ snake.health) * 0x100000001b3L;
                key = (key ^ snake.body.length) * 0x100000001b3L;
                for (int cell : snake.body) {
                    key = (key ^ (cell + 1L)) * 0x100000001b3L;
                }
            }
            key = (key ^ -1L) * 0x100000001b3L;
            for (int cell = 0; cell < geo.cells; cell++) {
                if (s.food[cell]) {
                    key = (key ^ (cell + 1L)) * 0x100000001b3L;
                }
            }
            // Geometria e hazards sao constantes dentro desta Search.
            key ^= key >>> 33;
            key *= 0xff51afd7ed558ccdL;
            return key ^ (key >>> 33);
        }

        // -----------------------------------------------------
        // Minimax com movimentos simultaneos (alpha-beta)
        //
        // valor(no) = max sobre meus movimentos de
        //             min sobre as respostas conjuntas dos inimigos
        // -----------------------------------------------------

        int value(World s, int depth, int alpha, int beta, boolean isRoot) {

            if (expired()) {
                return 0;
            }

            nodes++;

            int n = s.snakes.length;

            int alive = 0;

            for (int i = 1; i < n; i++) {
                if (s.snakes[i].alive) {
                    alive++;
                }
            }

            if (!s.snakes[0].alive) {
                return (hadOpponents && alive == 0) ? DRAW : LOSS - depth;
            }

            if (hadOpponents && alive == 0) {
                return WIN + depth;
            }

            if (depth == 0) {
                return eval(s);
            }

            int[] rel = computeRelease(s);

            int[] myMoves = isRoot ? rootOrder : orderMoves(s, 0, legalMoves(s, 0, rel), rel);

            long tableKey = useOrderTable ? positionKey(s) : 0L;
            int slot = (int) tableKey & (ORDER_TABLE_SIZE - 1);
            if (useOrderTable && orderHints[slot] >= 0 && orderKeys[slot] == tableKey) {
                // A sugestao so e aplicada se continuar entre os movimentos legais.
                promote(myMoves, orderHints[slot]);
            }

            // Inimigos ordenados por proximidade da nossa cabeca.
            int[] order = new int[alive];
            int c = 0;

            for (int i = 1; i < n; i++) {
                if (s.snakes[i].alive) {
                    order[c++] = i;
                }
            }

            int myHead = s.snakes[0].body[0];

            for (int a = 1; a < order.length; a++) {

                int key = order[a];
                int keyDist = geo.manhattan(s.snakes[key].body[0], myHead);
                int b = a - 1;

                while (b >= 0
                    && geo.manhattan(s.snakes[order[b]].body[0], myHead) > keyDist) {

                    order[b + 1] = order[b];
                    b--;
                }

                order[b + 1] = key;
            }

            int k = fullOpponentCount(s, order);

            int[] fullIdx = new int[k];
            int[][] fullMoves = new int[k][];
            int[] mv = new int[n];

            Arrays.fill(mv, -1);

            for (int j = 0; j < alive; j++) {

                int snakeIndex = order[j];

                if (j < k) {
                    fullIdx[j] = snakeIndex;
                    fullMoves[j] = orderMoves(s, snakeIndex, legalMoves(s, snakeIndex, rel), rel);
                } else {
                    mv[snakeIndex] = heuristicMove(s, snakeIndex, rel);
                }
            }

            int best = -INF;
            int bestMove = -1;
            int[] pos = new int[k];

            for (int m : myMoves) {

                // O desconto e constante para esta escolha da raiz. Os limites
                // precisam da mesma translacao para preservar a poda alpha-beta.
                int rootPenalty = isRoot ? rootSafetyPenalty[m] : 0;
                int branchAlpha = alpha + rootPenalty;
                int branchBeta = beta + rootPenalty;

                mv[0] = m;

                Arrays.fill(pos, 0);

                int cur = INF;

                while (true) {

                    if (expired()) {
                        return 0;
                    }

                    for (int j = 0; j < k; j++) {
                        mv[fullIdx[j]] = fullMoves[j][pos[j]];
                    }

                    World child = step(s, mv);

                    int v = value(child, depth - 1, branchAlpha,
                        Math.min(branchBeta, cur), false);

                    if (timeUp) {
                        return 0;
                    }

                    if (v < cur) {
                        cur = v;
                    }

                    if (cur <= branchAlpha) {
                        break;
                    }

                    // Proxima combinacao de respostas (odometro).
                    int j = k - 1;

                    while (j >= 0) {

                        pos[j]++;

                        if (pos[j] < fullMoves[j].length) {
                            break;
                        }

                        pos[j] = 0;
                        j--;
                    }

                    if (j < 0) {
                        break;
                    }
                }

                cur -= rootPenalty;
                if (cur > best) {

                    best = cur;
                    bestMove = m;

                    if (isRoot) {
                        rootBestMove = m;
                    }
                }

                if (best > alpha) {
                    alpha = best;
                }

                if (alpha >= beta) {
                    break;
                }
            }

            if (useOrderTable && bestMove >= 0) {
                orderKeys[slot] = tableKey;
                orderHints[slot] = bestMove;
            }
            return best;
        }

        /**
         * Preserva a busca dos dois inimigos mais proximos. A distancia entre
         * cabecas nao basta para descartar a influencia de corpos longos.
         * Todas as ameacas a ate duas casas tambem sao ramificadas.
         */
        int fullOpponentCount(World s, int[] order) {
            int count = Math.min(order.length, FULL_BRANCH_OPPONENTS);
            while (count < order.length
                && geo.manhattan(s.snakes[order[count]].body[0], s.snakes[0].body[0]) <= 2) {
                count++;
            }
            return count;
        }

        boolean expired() {
            if (timeUp || System.nanoTime() - deadline >= 0) {
                timeUp = true;
            }
            return timeUp;
        }

        // -----------------------------------------------------
        // Geracao de movimentos
        // -----------------------------------------------------

        /**
         * Movimentos que nao matam imediatamente por parede ou corpo.
         * Uma casa so esta bloqueada se ainda estiver ocupada depois do proximo
         * turno: o rabo comum sai do lugar, o rabo "empilhado" (cobra que acabou
         * de comer) nao.
         *
         * Se todos os movimentos matam, devolve um unico movimento (a cobra morre
         * na simulacao).
         */
        int[] legalMoves(World s, int i, int[] rel) {

            int[] body = s.snakes[i].body;
            int head = body[0];

            int[] result = new int[4];
            int c = 0;
            int anyDir = -1;

            for (int d = 0; d < 4; d++) {

                int next = geo.nbr[head][d];

                if (next < 0) {
                    continue;
                }

                if (anyDir < 0) {
                    anyDir = d;
                }

                if (body.length > 1 && next == body[1]) {
                    continue;
                }

                if (rel[next] > 1) {
                    continue;
                }

                result[c++] = d;
            }

            if (c == 0) {
                return new int[] {anyDir < 0 ? 0 : anyDir};
            }

            return Arrays.copyOf(result, c);
        }

        int healthAfterMove(World s, Snk snake, int next) {
            if (next < 0) {
                return 0;
            }
            return s.food[next] ? 100
                : snake.health - 1 - s.hazard[next] * HAZARD_DAMAGE;
        }

        /** Risco de perder um head-to-head, inclusive contra inimigos nao ramificados. */
        int headRisk(World s, int i, int next, int[] rel) {
            Snk me = s.snakes[i];
            int risk = 0;
            for (int j = 0; j < s.snakes.length; j++) {
                Snk other = s.snakes[j];
                if (j == i || !other.alive || other.body.length < me.body.length
                    || healthAfterMove(s, other, next) <= 0) {
                    continue;
                }
                for (int d : legalMoves(s, j, rel)) {
                    if (geo.nbr[other.body[0]][d] == next) {
                        risk++;
                        break;
                    }
                }
            }
            return risk;
        }

        int moveScore(World s, int i, int d, int[] rel) {
            Snk snake = s.snakes[i];
            int next = geo.nbr[snake.body[0]][d];
            if (next < 0 || rel[next] > 1) {
                return LOSS;
            }
            int hp = healthAfterMove(s, snake, next);
            if (hp <= 0) {
                return LOSS + hp;
            }
            int score = -20_000 * headRisk(s, i, next, rel);
            int exits = 0;
            for (int around : geo.nbr[next]) {
                if (around >= 0 && around != snake.body[0] && rel[around] <= 2) {
                    exits++;
                }
            }
            score += 70 * exits + hp - 8 * s.hazard[next] * HAZARD_DAMAGE;
            int nearest = UNREACHABLE;
            for (int cell = 0; cell < geo.cells; cell++) {
                if (s.food[cell]) {
                    nearest = Math.min(nearest, geo.manhattan(next, cell));
                }
            }
            if (nearest < UNREACHABLE) {
                score -= (snake.health < 40 ? 12 : 2) * nearest;
            }
            if (s.food[next]) {
                score += snake.health < 40 ? 600 : 90;
            }
            return score;
        }

        int[] orderMoves(World s, int i, int[] moves, int[] rel) {
            int[] scores = new int[moves.length];
            for (int a = 0; a < moves.length; a++) {
                scores[a] = moveScore(s, i, moves[a], rel);
            }
            for (int a = 1; a < moves.length; a++) {
                int move = moves[a];
                int score = scores[a];
                int b = a - 1;
                while (b >= 0 && scores[b] < score) {
                    moves[b + 1] = moves[b];
                    scores[b + 1] = scores[b];
                    b--;
                }
                moves[b + 1] = move;
                scores[b + 1] = score;
            }
            return moves;
        }

        /** Decisao de emergencia completa, mesmo com budget zero ou busca interrompida. */
        int fallbackMove(World s) {
            int[] rel = computeRelease(s).clone();
            int[] candidates = legalMoves(s, 0, rel);
            int[] moves = new int[s.snakes.length];
            Arrays.fill(moves, -1);
            for (int i = 1; i < s.snakes.length; i++) {
                if (s.snakes[i].alive) {
                    moves[i] = heuristicMove(s, i, rel);
                }
            }
            int bestMove = candidates[0];
            int bestScore = Integer.MIN_VALUE;
            for (int candidate : candidates) {
                int next = geo.nbr[s.snakes[0].body[0]][candidate];
                moves[0] = candidate;
                World child = step(s, moves);
                int score = child.snakes[0].alive ? eval(child) : LOSS;
                if (s == root) {
                    score -= rootSafetyPenalty[candidate];
                }
                if (next >= 0) {
                    score -= 20_000 * headRisk(s, 0, next, rel);
                }
                if (score > bestScore) {
                    bestScore = score;
                    bestMove = candidate;
                }
            }
            return bestMove;
        }

        /** Movimento unico e barato para inimigos distantes (nao ramificados). */
        int heuristicMove(World s, int i, int[] rel) {
            return orderMoves(s, i, legalMoves(s, i, rel), rel)[0];
        }

        // -----------------------------------------------------
        // Simulacao de um turno (Standard e hazards ja conhecidos).
        // Ordem oficial: mover, reduzir vida, hazard, comer, eliminar.
        // -----------------------------------------------------

        World step(World s, int[] mv) {

            int n = s.snakes.length;

            int[][] body = new int[n][];
            int[] health = new int[n];
            boolean[] contender = new boolean[n];
            boolean[] dead = new boolean[n];
            boolean[] ate = new boolean[n];

            boolean[] food = s.food;
            boolean foodCopied = false;

            for (int i = 0; i < n; i++) {

                Snk sn = s.snakes[i];

                if (!sn.alive) {
                    dead[i] = true;
                    body[i] = sn.body;
                    continue;
                }

                int next = mv[i] >= 0 ? geo.nbr[sn.body[0]][mv[i]] : -1;
                int len = sn.body.length;

                if (next < 0) {
                    // Saiu do tabuleiro.
                    dead[i] = true;
                    body[i] = sn.body;
                    continue;
                }

                int[] nb = new int[len];
                nb[0] = next;
                System.arraycopy(sn.body, 0, nb, 1, len - 1);
                body[i] = nb;

                int hp = healthAfterMove(s, sn, next);

                if (s.food[next]) {
                    ate[i] = true;
                }

                health[i] = hp;

                if (hp <= 0) {
                    dead[i] = true;
                    continue;
                }

                contender[i] = true;
            }

            // Crescimento e remocao da comida.
            for (int i = 0; i < n; i++) {

                if (!ate[i]) {
                    continue;
                }

                int[] nb = body[i];

                body[i] = Arrays.copyOf(nb, nb.length + 1);
                body[i][nb.length] = nb[nb.length - 1];

                if (!foodCopied) {
                    food = food.clone();
                    foodCopied = true;
                }

                food[nb[0]] = false;
            }

            // Colisoes (todas calculadas sobre o mesmo estado, ao mesmo tempo).
            boolean[] kill = new boolean[n];

            for (int i = 0; i < n; i++) {

                if (!contender[i]) {
                    continue;
                }

                int head = body[i][0];

                for (int j = 0; j < n && !kill[i]; j++) {

                    if (!contender[j]) {
                        continue;
                    }

                    int[] other = body[j];

                    for (int k = 1; k < other.length; k++) {
                        if (other[k] == head) {
                            kill[i] = true;
                            break;
                        }
                    }

                    if (!kill[i] && j != i
                        && other[0] == head
                        && other.length >= body[i].length) {

                        kill[i] = true;
                    }
                }
            }

            Snk[] next = new Snk[n];

            for (int i = 0; i < n; i++) {

                boolean isAlive = s.snakes[i].alive && !dead[i] && !kill[i];

                next[i] = isAlive
                    ? new Snk(body[i], health[i], true)
                    : new Snk(body[i], 0, false);
            }

            return new World(geo, s.hazard, food, next);
        }

        // -----------------------------------------------------
        // Estruturas auxiliares da avaliacao
        // -----------------------------------------------------

        /**
         * release[casa] = quantos turnos ate a casa ficar livre
         * (0 = ja esta livre; 1 = e um rabo que sai neste turno).
         */
        int[] computeRelease(World s) {

            Arrays.fill(release, 0);

            for (Snk sn : s.snakes) {

                if (!sn.alive) {
                    continue;
                }

                int len = sn.body.length;

                for (int k = 0; k < len; k++) {

                    int r = len - k;
                    int cell = sn.body[k];

                    if (r > release[cell]) {
                        release[cell] = r;
                    }
                }
            }

            return release;
        }

        /** Distancia (em turnos) ate cada casa, respeitando rabos que vao sair. */
        void bfs(World s, int i, int[] rel) {

            int[] d = dist[i];

            Arrays.fill(d, UNREACHABLE);

            int head = s.snakes[i].body[0];

            d[head] = 0;

            int qh = 0;
            int qt = 0;

            queue[qt++] = head;

            while (qh < qt) {

                int cell = queue[qh++];
                int nd = d[cell] + 1;

                for (int e = 0; e < 4; e++) {

                    int nn = geo.nbr[cell][e];

                    if (nn < 0 || d[nn] != UNREACHABLE) {
                        continue;
                    }

                    if (rel[nn] > nd) {
                        continue;
                    }

                    d[nn] = nd;
                    queue[qt++] = nn;
                }
            }
        }

        /**
         * Camaras livres sem atravessar a propria cabeca. Somar dois becos que
         * so se conectam pela cabeca cria uma estimativa de espaco enganosa.
         * Casas imediatamente disputadas com cabecas maiores/iguais nao
         * servem de entrada. Depois do primeiro passo a cabeca inimiga pode
         * sair, portanto essas casas nao viram paredes permanentes.
         */
        int immediateSpace(World s, int[] rel) {
            Arrays.fill(seen, false);
            Arrays.fill(headThreat, false);
            Snk me = s.snakes[0];
            for (int i = 1; i < s.snakes.length; i++) {
                Snk other = s.snakes[i];
                if (!other.alive || other.body.length < me.body.length) {
                    continue;
                }
                for (int move : legalMoves(s, i, rel)) {
                    int next = geo.nbr[other.body[0]][move];
                    if (next >= 0 && healthAfterMove(s, other, next) > 0) {
                        headThreat[next] = true;
                    }
                }
            }
            int head = s.snakes[0].body[0];
            seen[head] = true;
            int total = 1;
            largestChamber = 1;
            for (int entry : geo.nbr[head]) {
                if (entry < 0 || seen[entry] || rel[entry] > 1 || headThreat[entry]) {
                    continue;
                }
                int first = 0;
                int last = 0;
                seen[entry] = true;
                queue[last++] = entry;
                while (first < last) {
                    for (int next : geo.nbr[queue[first++]]) {
                        if (next >= 0 && !seen[next] && rel[next] <= 1) {
                            seen[next] = true;
                            queue[last++] = next;
                        }
                    }
                }
                total += last;
                largestChamber = Math.max(largestChamber, last + 1);
            }
            return total;
        }

        private static final class Path implements Comparable<Path> {
            final int cell;
            final int cost;
            final int turns;

            Path(int cell, int cost, int turns) {
                this.cell = cell;
                this.cost = cost;
                this.turns = turns;
            }

            @Override
            public int compareTo(Path other) {
                int comparison = Integer.compare(cost, other.cost);
                return comparison != 0 ? comparison : Integer.compare(turns, other.turns);
            }
        }

        /**
         * Dijkstra por vida gasta ate a primeira comida. O ultimo passo permite
         * comer com vida 1; comida no hazard tambem restaura a vida.
         * Retorna {custo, turnos, disputa}. Este e um indicador, nao uma simulacao
         * de trajetoria: liberacao futura de corpos continua sendo uma estimativa.
         */
        int[] bestFood(World s, int[] rel) {
            Arrays.fill(foodCost, UNREACHABLE);
            Arrays.fill(foodTurns, UNREACHABLE);
            paths.clear();
            Snk me = s.snakes[0];
            int head = me.body[0];
            foodCost[head] = 0;
            foodTurns[head] = 0;
            paths.add(new Path(head, 0, 0));
            int[] best = {UNREACHABLE, UNREACHABLE, 0};
            int bestScore = Integer.MAX_VALUE;
            while (!paths.isEmpty()) {
                Path path = paths.poll();
                if (path.cost != foodCost[path.cell] || path.turns != foodTurns[path.cell]) {
                    continue;
                }
                for (int next : geo.nbr[path.cell]) {
                    int turns = path.turns + 1;
                    if (next < 0 || rel[next] > turns) {
                        continue;
                    }
                    int cost = path.cost + 1;
                    if (s.food[next]) {
                        if (cost > me.health) {
                            continue;
                        }
                        int contested = 0;
                        for (int i = 1; i < s.snakes.length; i++) {
                            Snk other = s.snakes[i];
                            if (!other.alive || dist[i][next] > other.health) {
                                continue;
                            }
                            if (dist[i][next] < turns || (dist[i][next] == turns
                                && other.body.length >= me.body.length)) {
                                contested = 1;
                                break;
                            }
                        }
                        int score = cost + 30 * contested;
                        if (score < bestScore || (score == bestScore && turns < best[1])) {
                            bestScore = score;
                            best = new int[] {cost, turns, contested};
                        }
                        continue;
                    }
                    cost += s.hazard[next] * HAZARD_DAMAGE;
                    if (cost >= me.health) {
                        continue;
                    }
                    if (cost < foodCost[next]
                        || (cost == foodCost[next] && turns < foodTurns[next])) {
                        foodCost[next] = cost;
                        foodTurns[next] = turns;
                        paths.add(new Path(next, cost, turns));
                    }
                }
            }
            return best;
        }

        // -----------------------------------------------------
        // Avaliacao de uma posicao (do nosso ponto de vista)
        // -----------------------------------------------------

        int eval(World s) {

            int n = s.snakes.length;

            int[] rel = computeRelease(s);

            for (int i = 0; i < n; i++) {
                if (s.snakes[i].alive) {
                    bfs(s, i, rel);
                }
            }

            // Territorio (Voronoi): cada casa pertence a quem chega primeiro.
            // Empate de distancia: vence a cobra estritamente maior, senao ninguem.
            int[] territory = new int[n];

            for (int c = 0; c < geo.cells; c++) {

                int bestD = UNREACHABLE;

                for (int i = 0; i < n; i++) {
                    if (s.snakes[i].alive && dist[i][c] < bestD) {
                        bestD = dist[i][c];
                    }
                }

                if (bestD >= UNREACHABLE) {
                    continue;
                }

                int winner = -1;
                int winnerLen = -1;
                boolean unique = true;

                for (int i = 0; i < n; i++) {

                    if (!s.snakes[i].alive || dist[i][c] != bestD) {
                        continue;
                    }

                    int len = s.snakes[i].body.length;

                    if (len > winnerLen) {
                        winnerLen = len;
                        winner = i;
                        unique = true;
                    } else if (len == winnerLen) {
                        unique = false;
                    }
                }

                if (unique) {
                    territory[winner]++;
                }
            }

            Snk me = s.snakes[0];
            int myLen = me.body.length;

            int oppMaxCells = 0;
            int oppMaxLen = 0;

            for (int i = 1; i < n; i++) {

                if (!s.snakes[i].alive) {
                    continue;
                }

                oppMaxCells = Math.max(oppMaxCells, territory[i]);
                oppMaxLen = Math.max(oppMaxLen, s.snakes[i].body.length);
            }

            // Espaco alcancavel estimando quando os corpos atuais ficam livres.
            int selfSpace = 0;
            boolean hasFood = false;

            for (int c = 0; c < geo.cells; c++) {

                int d = dist[0][c];

                if (d >= UNREACHABLE) {
                    continue;
                }

                selfSpace++;

            }
            for (boolean food : s.food) {
                hasFood |= food;
            }

            int[] meal = hasFood ? bestFood(s, rel) : new int[] {UNREACHABLE, UNREACHABLE, 0};
            int foodDist = meal[1];
            immediateSpace(s, rel);
            boolean tailAccessible = seen[me.body[myLen - 1]];

            int score = 0;

            // Territorio: o que mais separa cobras fortes de fracas.
            score += 16 * territory[0] - 6 * oppMaxCells;

            // Tamanho: ser maior vence os head-to-head.
            int diff = oppMaxLen == 0
                ? 0
                : Math.max(-4, Math.min(4, myLen - oppMaxLen));

            score += 25 * diff + 6 * myLen;
            // Uma grande desvantagem continua relevante alem do limite +/-4,
            // mas nao deve dominar a seguranca por causa de um inimigo gigante.
            int deficit = Math.max(0, oppMaxLen - myLen);
            score -= 4 * Math.min(20, Math.max(0, deficit - 4));

            // Armadilha: regiao menor que o proprio corpo.
            if (selfSpace < myLen) {
                score -= 3000 + 300 * (myLen - selfSpace);
            }

            // Sem caminho para o rabo, depender de corpos futuros e arriscado.
            if (largestChamber < myLen + 2 && !tailAccessible) {
                score -= 180 * (myLen + 2 - largestChamber);
            }
            score += 4 * Math.min(largestChamber, myLen * 3);
            int mobility = 0;
            for (int move : legalMoves(s, 0, rel)) {
                int next = geo.nbr[me.body[0]][move];
                if (next >= 0 && rel[next] <= 1 && healthAfterMove(s, me, next) > 0
                    && headRisk(s, 0, next, rel) == 0) {
                    mobility++;
                }
            }
            score += 80 * mobility;
            if (mobility == 0) {
                score -= 4000;
            }

            // Sem comida visivel, nao presume que nunca havera novos spawns.
            if (me.health < 35) {
                score -= (35 - me.health) * 20;
            }

            int pull = 2
                + (me.health < 60 ? (60 - me.health) / 4 : 0)
                + (diff <= 0 ? 2 : 0);

            // Crescer antes de ficar com fome, somente com margem de espaco
            // e comida sem disputa conhecida. Comer tambem pode fechar a saida.
            if (oppMaxLen > 0 && myLen <= oppMaxLen && me.health >= 60 && meal[2] == 0
                && (largestChamber >= myLen + 3 || tailAccessible)) {
                pull += 6 + Math.min(6, deficit / 3);
            }

            if (hasFood && meal[0] < UNREACHABLE) {
                score -= pull * Math.min(foodDist, 25);
                score -= pull * Math.min(meal[0], 100);
                score -= meal[2] * (me.health < 35 ? 500 : 150);
            } else if (hasFood) {
                // Comida inacessivel e um alerta, nao uma morte comprovada:
                // a estimativa de trajetoria e aproximada e novas frutas podem surgir.
                score -= 150 + (100 - me.health) * 5;
            }
            score += me.health / 5;

            if (s.hazard[me.body[0]] > 0) {
                score -= 4 * HAZARD_DAMAGE * s.hazard[me.body[0]];
            }

            // Resultado terminal sempre supera qualquer avaliacao heuristica.
            return Math.max(LOSS / 2, Math.min(WIN / 2, score));
        }
    }
}
