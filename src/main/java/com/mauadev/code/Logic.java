package com.mauadev.code;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.mauadev.code.entities.Coordinate;
import com.mauadev.code.entities.GameState;
import com.mauadev.code.entities.Snake;

/**
 * Bot de Battlesnake baseado em busca adversarial.
 *
 * Ideia geral:
 *  1. O estado do jogo e convertido em um modelo interno que simula as regras
 *     oficiais (movimento, rabo que sai, comida, hazard, colisoes, head-to-head).
 *  2. Uma busca com aprofundamento iterativo (minimax com movimentos simultaneos
 *     e poda alpha-beta) escolhe o movimento que da o melhor resultado no pior
 *     caso, considerando TODAS as cobras inimigas.
 *  3. Nas folhas, o estado e avaliado por territorio (Voronoi), espaco
 *     alcancavel, tamanho relativo, fome e distancia da comida.
 *
 * Nao ha estado estatico mutavel: varias partidas podem ser jogadas em paralelo.
 */
public class Logic {

    // =========================================================
    // CONFIGURACAO
    // =========================================================

    /** Tempo maximo de busca por jogada (o limite do Battlesnake e 500 ms). */
    private static final long TIME_BUDGET_NANOS =
        Long.getLong("snake.budget.ms", 300L) * 1_000_000L;

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

        long deadline = System.nanoTime() + TIME_BUDGET_NANOS;
        World world = null;

        try {
            world = buildWorld(state);

            if (world == null) {
                return "up";
            }

            return NAMES[new Search(world, deadline).run()];

        } catch (RuntimeException e) {

            // Nunca devolve erro HTTP por causa de um bug: joga o movimento
            // legal mais simples.
            try {
                if (world != null) {
                    Search s = new Search(world, Long.MAX_VALUE);
                    return NAMES[s.legalMoves(world, 0, s.computeRelease(world))[0]];
                }
            } catch (RuntimeException ignored) {
                // cai no retorno padrao
            }

            return "up";
        }
    }

    // =========================================================
    // GEOMETRIA DO TABULEIRO
    // =========================================================

    /** Tabela de vizinhos pre-calculada. Celula = y * largura + x. */
    private static final class Geo {

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
            return Math.abs(a % w - b % w) + Math.abs(a / w - b / w);
        }
    }

    // =========================================================
    // MODELO INTERNO DO JOGO
    // =========================================================

    /** Cobra imutavel. body[0] e a cabeca. Segmentos empilhados aparecem repetidos. */
    private static final class Snk {

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
    private static final class World {

        final Geo geo;
        final boolean[] hazard;
        final boolean[] food;
        final Snk[] snakes;

        World(Geo geo, boolean[] hazard, boolean[] food, Snk[] snakes) {
            this.geo = geo;
            this.hazard = hazard;
            this.food = food;
            this.snakes = snakes;
        }
    }

    private static World buildWorld(GameState state) {

        if (state == null || state.getBoard() == null || state.getYou() == null) {
            return null;
        }

        int w = state.getBoard().getWidth();
        int h = state.getBoard().getHeight();

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
        boolean[] hazard = new boolean[geo.cells];

        mark(geo, state.getBoard().getFood(), food);
        mark(geo, state.getBoard().getHazards(), hazard);

        return new World(geo, hazard, food, all.toArray(new Snk[0]));
    }

    private static boolean isSameSnake(Snake you, Snake other) {

        if (you.getId() != null && you.getId().equals(other.getId())) {
            return true;
        }

        if (you.getId() == null && other.getId() == null
            && you.getBody() != null && other.getBody() != null
            && !you.getBody().isEmpty() && !other.getBody().isEmpty()) {

            Coordinate a = you.getBody().get(0);
            Coordinate b = other.getBody().get(0);

            return a.getX() == b.getX() && a.getY() == b.getY();
        }

        return false;
    }

    private static Snk toSnk(Geo geo, Snake snake) {

        List<Coordinate> body = snake.getBody();

        if (body == null || body.isEmpty()) {
            return null;
        }

        int[] cells = new int[body.size()];

        for (int i = 0; i < cells.length; i++) {

            Coordinate c = body.get(i);

            if (c.getX() < 0 || c.getY() < 0
                || c.getX() >= geo.w || c.getY() >= geo.h) {
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

            if (c.getX() >= 0 && c.getY() >= 0
                && c.getX() < geo.w && c.getY() < geo.h) {

                target[c.getY() * geo.w + c.getX()] = true;
            }
        }
    }

    // =========================================================
    // BUSCA
    // =========================================================

    private static final class Search {

        final Geo geo;
        final World root;
        final long deadline;
        final boolean hadOpponents;

        // Buffers reutilizaveis (a busca e single-thread).
        final int[] release;
        final int[][] dist;
        final int[] queue;

        boolean timeUp;
        long nodes;

        int[] rootOrder;
        int rootBestMove;

        Search(World root, long deadline) {
            this.geo = root.geo;
            this.root = root;
            this.deadline = deadline;
            this.hadOpponents = root.snakes.length > 1;

            this.release = new int[geo.cells];
            this.dist = new int[root.snakes.length][geo.cells];
            this.queue = new int[geo.cells];
        }

        // -----------------------------------------------------
        // Aprofundamento iterativo
        // -----------------------------------------------------

        int run() {

            int[] candidates = legalMoves(root, 0, computeRelease(root));

            if (candidates.length == 1) {
                return candidates[0];
            }

            rootOrder = candidates;

            int best = candidates[0];

            for (int depth = 1; depth <= MAX_DEPTH; depth++) {

                rootBestMove = -1;

                int value = value(root, depth, -INF, INF, true);

                if (timeUp) {
                    break;
                }

                if (rootBestMove >= 0) {
                    best = rootBestMove;
                    moveToFront(best);
                }

                // Vitoria forcada encontrada: nao ha o que melhorar.
                if (value >= WIN) {
                    break;
                }
            }

            return best;
        }

        void moveToFront(int move) {

            int[] order = new int[rootOrder.length];
            int c = 0;

            order[c++] = move;

            for (int m : rootOrder) {
                if (m != move) {
                    order[c++] = m;
                }
            }

            rootOrder = order;
        }

        // -----------------------------------------------------
        // Minimax com movimentos simultaneos (alpha-beta)
        //
        // valor(no) = max sobre meus movimentos de
        //             min sobre as respostas conjuntas dos inimigos
        // -----------------------------------------------------

        int value(World s, int depth, int alpha, int beta, boolean isRoot) {

            if (timeUp) {
                return 0;
            }

            if ((++nodes & 127L) == 0 && System.nanoTime() >= deadline) {
                timeUp = true;
                return 0;
            }

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

            int[] myMoves = isRoot ? rootOrder : legalMoves(s, 0, rel);

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

            int k = Math.min(alive, FULL_BRANCH_OPPONENTS);

            int[] fullIdx = new int[k];
            int[][] fullMoves = new int[k][];
            int[] mv = new int[n];

            Arrays.fill(mv, -1);

            for (int j = 0; j < alive; j++) {

                int snakeIndex = order[j];

                if (j < k) {
                    fullIdx[j] = snakeIndex;
                    fullMoves[j] = legalMoves(s, snakeIndex, rel);
                } else {
                    mv[snakeIndex] = heuristicMove(s, snakeIndex, rel);
                }
            }

            int best = -INF;
            int[] pos = new int[k];

            for (int m : myMoves) {

                mv[0] = m;

                Arrays.fill(pos, 0);

                int cur = INF;

                while (true) {

                    for (int j = 0; j < k; j++) {
                        mv[fullIdx[j]] = fullMoves[j][pos[j]];
                    }

                    World child = step(s, mv);

                    int v = value(child, depth - 1, alpha, Math.min(beta, cur), false);

                    if (timeUp) {
                        return 0;
                    }

                    if (v < cur) {
                        cur = v;
                    }

                    if (cur <= alpha) {
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

                if (cur > best) {

                    best = cur;

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

            return best;
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

        /** Movimento unico e barato para inimigos distantes (nao ramificados). */
        int heuristicMove(World s, int i, int[] rel) {

            int[] legal = legalMoves(s, i, rel);

            Snk snake = s.snakes[i];
            int head = snake.body[0];

            int bestDir = legal[0];
            int bestScore = Integer.MIN_VALUE;

            for (int d : legal) {

                int next = geo.nbr[head][d];

                if (next < 0) {
                    continue;
                }

                int score = 0;

                for (int e = 0; e < 4; e++) {

                    int around = geo.nbr[next][e];

                    if (around >= 0 && rel[around] <= 1) {
                        score += 10;
                    }
                }

                if (s.food[next]) {
                    score += snake.health < 50 ? 25 : 8;
                }

                if (score > bestScore) {
                    bestScore = score;
                    bestDir = d;
                }
            }

            return bestDir;
        }

        // -----------------------------------------------------
        // Simulacao exata de um turno
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

                int hp = sn.health - 1;

                if (s.food[next]) {
                    ate[i] = true;
                    hp = 100;
                } else if (s.hazard[next]) {
                    hp -= HAZARD_DAMAGE;
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

            // Espaco que consigo alcancar ignorando os inimigos.
            int selfSpace = 0;
            int foodDist = UNREACHABLE;

            for (int c = 0; c < geo.cells; c++) {

                int d = dist[0][c];

                if (d >= UNREACHABLE) {
                    continue;
                }

                selfSpace++;

                if (s.food[c] && d < foodDist) {
                    foodDist = d;
                }
            }

            int score = 0;

            // Territorio: o que mais separa cobras fortes de fracas.
            score += 16 * territory[0] - 6 * oppMaxCells;

            // Tamanho: ser maior vence os head-to-head.
            int diff = oppMaxLen == 0
                ? 0
                : Math.max(-4, Math.min(4, myLen - oppMaxLen));

            score += 25 * diff + 6 * myLen;

            // Armadilha: regiao menor que o proprio corpo.
            if (selfSpace < myLen) {
                score -= 3000 + 300 * (myLen - selfSpace);
            }

            // Fome: nao consegue chegar a nenhuma comida a tempo.
            if (me.health < foodDist) {
                score -= 25_000;
            }

            if (me.health < 35) {
                score -= (35 - me.health) * 20;
            }

            int pull = 2
                + (me.health < 60 ? (60 - me.health) / 4 : 0)
                + (diff <= 0 ? 2 : 0);

            score -= pull * Math.min(foodDist, 25);

            if (s.hazard[me.body[0]]) {
                score -= 60;
            }

            return score;
        }
    }
}