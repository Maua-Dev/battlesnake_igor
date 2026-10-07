package com.mauadev.code;
// Documentacao: https://docs.battlesnake.com

import com.mauadev.code.entities.Coordinate;
import com.mauadev.code.entities.GameState;
import com.mauadev.code.entities.Snake;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

public class Logic {

    private static final int INFINITO = 1_000_000;

    private enum Comportamento {
        ALIMENTAR,
        CACAR
    }

    // ---------------------------------------------------------
    // INFORMACOES DA COBRA
    // ---------------------------------------------------------

    public static Map<String, String> info() {
        Map<String, String> info = new HashMap<>();
        info.put("apiversion", "1");
        info.put("author", "");
        info.put("color", "#8B0000");
        info.put("head", "tiger-king");
        info.put("tail", "hook");
        return info;
    }

    public static void start(GameState state) {
    }

    public static void end(GameState state) {
    }

    // ---------------------------------------------------------
    // DECISAO PRINCIPAL
    // ---------------------------------------------------------

    public static String getMove(GameState state) {

        Snake me = state.getYou();
        Coordinate head = me.getHead();

        List<Snake> enemies = getEnemies(state);
        List<String> safeMoves = getSafeMoves(state, me);

        // Nenhum movimento realmente seguro.
        if (safeMoves.isEmpty()) {
            return emergencyMove(state, me);
        }

        Comportamento comportamento =
                escolherComportamento(me, enemies);

        int bestScore = Integer.MIN_VALUE;
        List<String> bestMoves = new ArrayList<>();

        // Calcula previamente o mapa dos adversarios.
        List<int[][]> enemyDistances = new ArrayList<>();

        for (Snake enemy : enemies) {
            enemyDistances.add(
                    dijkstra(state, enemy.getHead(), enemy)
            );
        }

        for (String move : safeMoves) {

            Coordinate next = move(head, move);

            int score = avaliarMovimento(
                    state,
                    me,
                    enemies,
                    enemyDistances,
                    next,
                    comportamento
            );

            if (score > bestScore) {
                bestScore = score;
                bestMoves.clear();
                bestMoves.add(move);

            } else if (score == bestScore) {
                bestMoves.add(move);
            }
        }

        return bestMoves.get(
                ThreadLocalRandom.current().nextInt(bestMoves.size())
        );
    }

    // ---------------------------------------------------------
    // COMPORTAMENTO
    // ---------------------------------------------------------

    private static Comportamento escolherComportamento(
            Snake me,
            List<Snake> enemies
    ) {

        // Com pouca vida, comida sempre ganha da caça.
        if (me.getHealth() <= 40) {
            return Comportamento.ALIMENTAR;
        }

        if (enemies.isEmpty()) {
            return Comportamento.ALIMENTAR;
        }

        int maiorInimigo = 0;

        for (Snake enemy : enemies) {
            maiorInimigo = Math.max(
                    maiorInimigo,
                    enemy.getLength()
            );
        }

        // So caca se realmente possuir vantagem.
        if (me.getLength() >= 7 &&
                me.getLength() >= maiorInimigo + 2 &&
                me.getHealth() >= 50) {

            return Comportamento.CACAR;
        }

        return Comportamento.ALIMENTAR;
    }

    // ---------------------------------------------------------
    // AVALIACAO
    // ---------------------------------------------------------

    private static int avaliarMovimento(
            GameState state,
            Snake me,
            List<Snake> enemies,
            List<int[][]> enemyDistances,
            Coordinate next,
            Comportamento comportamento
    ) {

        int score = 0;

        // 1. Espaco disponivel.
        int space = floodFill(state, next);

        score += space * 8;

        // Entrar em espaco menor que o proprio corpo e perigoso.
        if (space <= me.getLength()) {
            score -= 2500;
        }

        // 2. Hazards.
        if (isHazard(state, next)) {
            score -= 300;
        }

        // 3. Alimentacao.
        score += foodScore(
                state,
                me,
                enemies,
                enemyDistances,
                next,
                comportamento
        );

        // 4. Caca.
        if (comportamento == Comportamento.CACAR) {

            score += huntScore(
                    state,
                    me,
                    enemies,
                    next
            );
        }

        // 5. Evita ficar grudado na parede sem necessidade.
        score += wallScore(state, next);

        return score;
    }

    // ---------------------------------------------------------
    // ALIMENTACAO
    // ---------------------------------------------------------

    private static int foodScore(
            GameState state,
            Snake me,
            List<Snake> enemies,
            List<int[][]> enemyDistances,
            Coordinate start,
            Comportamento comportamento
    ) {

        List<Coordinate> foods = state.getBoard().getFood();

        if (foods == null || foods.isEmpty()) {
            return 0;
        }

        int[][] myDistances = dijkstra(
                state,
                start,
                me
        );

        int bestFoodDistance = INFINITO;
        boolean encontrouComidaSegura = false;

        for (Coordinate food : foods) {

            int myDistance =
                    myDistances[food.getY()][food.getX()];

            if (myDistance >= INFINITO) {
                continue;
            }

            // Ja gastamos um turno para chegar em "start".
            int myArrival = myDistance + 1;

            boolean safeFood = true;

            for (int i = 0; i < enemies.size(); i++) {

                Snake enemy = enemies.get(i);

                int enemyDistance =
                        enemyDistances.get(i)
                                [food.getY()]
                                [food.getX()];

                if (enemyDistance >= INFINITO) {
                    continue;
                }

                /*
                 * Se o inimigo maior ou igual chega primeiro
                 * ou junto, nao vale disputar.
                 */
                if (enemy.getLength() >= me.getLength() &&
                        enemyDistance <= myArrival) {

                    safeFood = false;
                    break;
                }
            }

            if (!safeFood) {
                continue;
            }

            encontrouComidaSegura = true;

            bestFoodDistance = Math.min(
                    bestFoodDistance,
                    myDistance
            );
        }

        if (!encontrouComidaSegura) {
            return 0;
        }

        int weight;

        // Quanto menor a vida, mais importante fica a comida.
        if (me.getHealth() <= 20) {
            weight = 100;

        } else if (me.getHealth() <= 40) {
            weight = 65;

        } else if (me.getHealth() <= 60) {
            weight = 35;

        } else if (comportamento == Comportamento.CACAR) {
            weight = 5;

        } else {
            weight = 15;
        }

        // Quanto menor a distancia, maior a pontuacao.
        return Math.max(
                0,
                20 - bestFoodDistance
        ) * weight;
    }

    // ---------------------------------------------------------
    // CACA
    // ---------------------------------------------------------

    private static int huntScore(
            GameState state,
            Snake me,
            List<Snake> enemies,
            Coordinate next
    ) {

        int bestScore = 0;

        int[][] myDistances =
                dijkstra(state, next, me);

        for (Snake enemy : enemies) {

            // Nao tenta cacar cobra maior ou igual.
            if (enemy.getLength() >= me.getLength()) {
                continue;
            }

            List<Coordinate> enemyMoves =
                    getPossibleEnemyMoves(state, enemy);

            if (enemyMoves.isEmpty()) {
                // Inimigo encurralado.
                bestScore = Math.max(bestScore, 800);
                continue;
            }

            int minDistance = INFINITO;

            for (Coordinate enemyNext : enemyMoves) {

                // Podemos ganhar uma colisao frontal.
                if (same(next, enemyNext) &&
                        me.getLength() > enemy.getLength()) {

                    bestScore = Math.max(
                            bestScore,
                            1200
                    );
                }

                int distance =
                        myDistances[enemyNext.getY()]
                                   [enemyNext.getX()];

                minDistance = Math.min(
                        minDistance,
                        distance
                );
            }

            if (minDistance < INFINITO) {

                // Aproximar-se das rotas de fuga do inimigo.
                int pressure =
                        Math.max(0, 15 - minDistance) * 20;

                /*
                 * Quanto menos movimentos o inimigo possui,
                 * melhor nossa pressao.
                 */
                pressure +=
                        (4 - enemyMoves.size()) * 70;

                /*
                 * Quanto maior nossa vantagem de tamanho,
                 * mais agressivos podemos ser.
                 */
                pressure +=
                        (me.getLength() - enemy.getLength()) * 15;

                bestScore = Math.max(
                        bestScore,
                        pressure
                );
            }
        }

        return bestScore;
    }

    // ---------------------------------------------------------
    // MOVIMENTOS SEGUROS
    // ---------------------------------------------------------

    private static List<String> getSafeMoves(
            GameState state,
            Snake me
    ) {

        List<String> safeMoves =
                new ArrayList<>(
                        Arrays.asList(
                                "up",
                                "down",
                                "left",
                                "right"
                        )
                );

        Coordinate head = me.getHead();

        safeMoves.removeIf(direction -> {

            Coordinate next = move(
                    head,
                    direction
            );

            // Parede.
            if (!insideBoard(state, next)) {
                return true;
            }

            // Corpo.
            if (occupied(state, next)) {
                return true;
            }

            // Possivel head-to-head perigoso.
            for (Snake enemy : getEnemies(state)) {

                if (enemy.getLength() < me.getLength()) {
                    continue;
                }

                List<Coordinate> enemyMoves =
                        getPossibleEnemyMoves(state, enemy);

                for (Coordinate enemyNext : enemyMoves) {

                    if (same(next, enemyNext)) {
                        return true;
                    }
                }
            }

            return false;
        });

        return safeMoves;
    }

    // ---------------------------------------------------------
    // POSSIVEIS MOVIMENTOS DO INIMIGO
    // ---------------------------------------------------------

    private static List<Coordinate> getPossibleEnemyMoves(
            GameState state,
            Snake enemy
    ) {

        List<Coordinate> positions =
                new ArrayList<>();

        Coordinate head = enemy.getHead();

        for (String direction :
                Arrays.asList("up", "down", "left", "right")) {

            Coordinate next =
                    move(head, direction);

            if (!insideBoard(state, next)) {
                continue;
            }

            // Impede voltar diretamente contra o pescoco.
            List<Coordinate> body = enemy.getBody();

            if (body != null && body.size() >= 2) {

                Coordinate neck = body.get(1);

                if (same(next, neck)) {
                    continue;
                }
            }

            if (occupied(state, next)) {
                continue;
            }

            positions.add(next);
        }

        return positions;
    }

    // ---------------------------------------------------------
    // DIJKSTRA
    // ---------------------------------------------------------

    private static int[][] dijkstra(
            GameState state,
            Coordinate start,
            Snake snake
    ) {

        int width = state.getBoard().getWidth();
        int height = state.getBoard().getHeight();

        int[][] distance =
                new int[height][width];

        for (int[] row : distance) {
            Arrays.fill(row, INFINITO);
        }

        PriorityQueue<Node> queue =
                new PriorityQueue<>(
                        Comparator.comparingInt(n -> n.distance)
                );

        distance[start.getY()][start.getX()] = 0;

        queue.add(
                new Node(
                        start.getX(),
                        start.getY(),
                        0
                )
        );

        while (!queue.isEmpty()) {

            Node current = queue.poll();

            if (current.distance !=
                    distance[current.y][current.x]) {
                continue;
            }

            for (int[] dir : DIRECTIONS) {

                int nx = current.x + dir[0];
                int ny = current.y + dir[1];

                Coordinate next =
                        coordinate(nx, ny);

                if (!insideBoard(state, next)) {
                    continue;
                }

                /*
                 * O ponto inicial pode estar ocupado porque
                 * e a cabeca de uma cobra.
                 */
                if (occupied(state, next) &&
                        !same(next, start)) {

                    continue;
                }

                int movementCost = 1;

                // Hazards continuam possiveis, mas sao caros.
                if (isHazard(state, next)) {
                    movementCost += 15;
                }

                /*
                 * Regioes onde uma cobra maior pode chegar
                 * ficam mais caras.
                 */
                for (Snake enemy : getEnemiesOf(state, snake)) {

                    if (enemy.getLength() < snake.getLength()) {
                        continue;
                    }

                    for (Coordinate danger :
                            getPossibleEnemyMoves(state, enemy)) {

                        if (same(next, danger)) {
                            movementCost += 40;
                        }
                    }
                }

                int newDistance =
                        current.distance + movementCost;

                if (newDistance < distance[ny][nx]) {

                    distance[ny][nx] =
                            newDistance;

                    queue.add(
                            new Node(
                                    nx,
                                    ny,
                                    newDistance
                            )
                    );
                }
            }
        }

        return distance;
    }

    // ---------------------------------------------------------
    // FLOOD FILL
    // ---------------------------------------------------------

    private static int floodFill(
            GameState state,
            Coordinate start
    ) {

        int width = state.getBoard().getWidth();
        int height = state.getBoard().getHeight();

        boolean[][] visited =
                new boolean[height][width];

        Queue<Coordinate> queue =
                new ArrayDeque<>();

        queue.add(start);

        visited[start.getY()][start.getX()] = true;

        int space = 0;

        while (!queue.isEmpty()) {

            Coordinate current = queue.poll();

            space++;

            for (int[] dir : DIRECTIONS) {

                int nx = current.getX() + dir[0];
                int ny = current.getY() + dir[1];

                Coordinate next =
                        coordinate(nx, ny);

                if (!insideBoard(state, next)) {
                    continue;
                }

                if (visited[ny][nx]) {
                    continue;
                }

                if (occupied(state, next) &&
                        !same(next, start)) {

                    continue;
                }

                visited[ny][nx] = true;
                queue.add(next);
            }
        }

        return space;
    }

    // ---------------------------------------------------------
    // PAREDES
    // ---------------------------------------------------------

    private static int wallScore(
            GameState state,
            Coordinate position
    ) {

        int width = state.getBoard().getWidth();
        int height = state.getBoard().getHeight();

        int distanceLeft = position.getX();
        int distanceRight =
                width - 1 - position.getX();

        int distanceDown = position.getY();
        int distanceUp =
                height - 1 - position.getY();

        int nearestWall =
                Math.min(
                        Math.min(distanceLeft, distanceRight),
                        Math.min(distanceDown, distanceUp)
                );

        // Pequeno incentivo para nao ficar preso nas bordas.
        return nearestWall * 2;
    }

    // ---------------------------------------------------------
    // EMERGENCIA
    // ---------------------------------------------------------

    private static String emergencyMove(
            GameState state,
            Snake me
    ) {

        List<String> possible =
                new ArrayList<>();

        for (String direction :
                Arrays.asList("up", "down", "left", "right")) {

            Coordinate next =
                    move(me.getHead(), direction);

            if (insideBoard(state, next)) {
                possible.add(direction);
            }
        }

        if (possible.isEmpty()) {
            return "up";
        }

        return possible.get(
                ThreadLocalRandom.current()
                        .nextInt(possible.size())
        );
    }

    // ---------------------------------------------------------
    // AUXILIARES
    // ---------------------------------------------------------

    private static final int[][] DIRECTIONS = {
            {1, 0},
            {-1, 0},
            {0, 1},
            {0, -1}
    };

    private static Coordinate move(
            Coordinate position,
            String direction
    ) {

        int x = position.getX();
        int y = position.getY();

        switch (direction) {

            case "up":
                y++;
                break;

            case "down":
                y--;
                break;

            case "left":
                x--;
                break;

            case "right":
                x++;
                break;
        }

        return coordinate(x, y);
    }

    private static Coordinate coordinate(
            int x,
            int y
    ) {

        Coordinate coordinate =
                new Coordinate();

        coordinate.setX(x);
        coordinate.setY(y);

        return coordinate;
    }

    private static boolean insideBoard(
            GameState state,
            Coordinate position
    ) {

        return position.getX() >= 0 &&
                position.getY() >= 0 &&
                position.getX() < state.getBoard().getWidth() &&
                position.getY() < state.getBoard().getHeight();
    }

    private static boolean occupied(
            GameState state,
            Coordinate position
    ) {

        if (state.getYou() != null &&
                contains(
                        state.getYou().getBody(),
                        position
                )) {

            return true;
        }

        if (state.getBoard() == null ||
                state.getBoard().getSnakes() == null) {

            return false;
        }

        for (Snake snake :
                state.getBoard().getSnakes()) {

            if (contains(
                    snake.getBody(),
                    position
            )) {

                return true;
            }
        }

        return false;
    }

    private static boolean contains(
            List<Coordinate> coordinates,
            Coordinate target
    ) {

        if (coordinates == null) {
            return false;
        }

        for (Coordinate coordinate : coordinates) {

            if (same(coordinate, target)) {
                return true;
            }
        }

        return false;
    }

    private static boolean same(
            Coordinate a,
            Coordinate b
    ) {

        return a != null &&
                b != null &&
                a.getX() == b.getX() &&
                a.getY() == b.getY();
    }

    private static boolean isHazard(
            GameState state,
            Coordinate position
    ) {

        return state.getBoard().getHazards() != null &&
                contains(
                        state.getBoard().getHazards(),
                        position
                );
    }

    private static List<Snake> getEnemies(
            GameState state
    ) {

        return getEnemiesOf(
                state,
                state.getYou()
        );
    }

    private static List<Snake> getEnemiesOf(
            GameState state,
            Snake snake
    ) {

        List<Snake> enemies =
                new ArrayList<>();

        if (state.getBoard() == null ||
                state.getBoard().getSnakes() == null) {

            return enemies;
        }

        for (Snake other :
                state.getBoard().getSnakes()) {

            if (snake != null &&
                    snake.getId() != null &&
                    snake.getId().equals(other.getId())) {

                continue;
            }

            enemies.add(other);
        }

        return enemies;
    }

    // ---------------------------------------------------------
    // NODE DO DIJKSTRA
    // ---------------------------------------------------------

    private static class Node {

        int x;
        int y;
        int distance;

        Node(
                int x,
                int y,
                int distance
        ) {

            this.x = x;
            this.y = y;
            this.distance = distance;
        }
    }
}