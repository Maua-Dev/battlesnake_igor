package com.mauadev.code;
// Documentacao: https://docs.battlesnake.com

import com.mauadev.code.entities.Coordinate;
import com.mauadev.code.entities.GameState;
import com.mauadev.code.entities.Snake;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Queue;
import java.util.concurrent.ThreadLocalRandom;

public class Logic {

    private static final int INFINITO = 1_000_000;

    private static final int[][] DIRECTIONS = {
        {1, 0},
        {-1, 0},
        {0, 1},
        {0, -1}
    };

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
        info.put("color", "#3d98ff");
        info.put("head", "beluga");
        info.put("tail", "do-sammy");
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
        Coordinate myHead = me.getHead();

        List<Snake> enemies = getEnemies(state);
        List<String> safeMoves = getSafeMoves(state, me);

        if (safeMoves.isEmpty()) {
            return emergencyMove(state, me);
        }

        Comportamento comportamento =
            escolherComportamento(me, enemies);

        Coordinate foodTarget =
            escolherComidaAlvo(state, me, enemies);

        List<int[][]> enemyDistances = new ArrayList<>();

        for (Snake enemy : enemies) {
            enemyDistances.add(
                dijkstra(state, enemy.getHead(), enemy)
            );
        }

        int bestScore = Integer.MIN_VALUE;
        List<String> bestMoves = new ArrayList<>();

        for (String direction : safeMoves) {

            Coordinate next =
                move(myHead, direction);

            int score =
                avaliarMovimento(
                    state,
                    me,
                    enemies,
                    enemyDistances,
                    next,
                    comportamento,
                    foodTarget
                );

            if (score > bestScore) {
                bestScore = score;
                bestMoves.clear();
                bestMoves.add(direction);

            } else if (score == bestScore) {
                bestMoves.add(direction);
            }
        }

        return bestMoves.get(
            ThreadLocalRandom.current()
                .nextInt(bestMoves.size())
        );
    }

    // ---------------------------------------------------------
    // COMPORTAMENTO
    // ---------------------------------------------------------

    private static Comportamento escolherComportamento(
        Snake me,
        List<Snake> enemies
    ) {

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

        // Caca apenas quando estiver grande o suficiente.
        if (
            me.getLength() >= 11 &&
            me.getLength() >= maiorInimigo + 2 &&
            me.getHealth() >= 50
        ) {
            return Comportamento.CACAR;
        }

        return Comportamento.ALIMENTAR;
    }

    // ---------------------------------------------------------
    // ESCOLHA DA COMIDA-ALVO
    // ---------------------------------------------------------

    private static Coordinate escolherComidaAlvo(
        GameState state,
        Snake me,
        List<Snake> enemies
    ) {

        List<Coordinate> foods =
            state.getBoard().getFood();

        if (foods == null || foods.isEmpty()) {
            return null;
        }

        int[][] myDistances =
            dijkstra(state, me.getHead(), me);

        Coordinate melhorComida = null;
        int melhorCusto = INFINITO;

        for (Coordinate food : foods) {

            int minhaDistancia =
                myDistances[food.getY()][food.getX()];

            if (minhaDistancia >= INFINITO) {
                continue;
            }

            boolean segura = true;

            for (Snake enemy : enemies) {

                int[][] enemyDistances =
                    dijkstra(
                        state,
                        enemy.getHead(),
                        enemy
                    );

                int distanciaInimigo =
                    enemyDistances
                        [food.getY()]
                        [food.getX()];

                /*
                 * Se inimigo maior ou igual chega antes
                 * ou junto, nao disputa essa comida.
                 */
                if (
                    enemy.getLength() >= me.getLength() &&
                    distanciaInimigo <= minhaDistancia
                ) {
                    segura = false;
                    break;
                }
            }

            if (!segura) {
                continue;
            }

            if (minhaDistancia < melhorCusto) {
                melhorCusto = minhaDistancia;
                melhorComida = food;
            }
        }

        return melhorComida;
    }

    // ---------------------------------------------------------
    // AVALIACAO DE MOVIMENTO
    // ---------------------------------------------------------

    private static int avaliarMovimento(
        GameState state,
        Snake me,
        List<Snake> enemies,
        List<int[][]> enemyDistances,
        Coordinate next,
        Comportamento comportamento,
        Coordinate foodTarget
    ) {

        int score = 0;

        // -----------------------------------------------------
        // 1. ESPACO LIVRE
        // -----------------------------------------------------

        int space =
            floodFill(state, next);

        score += space * 8;

        if (space <= me.getLength()) {
            score -= 2500;
        }

        // -----------------------------------------------------
        // 2. HAZARD
        // -----------------------------------------------------

        if (isHazard(state, next)) {
            score -= 300;
        }

        // -----------------------------------------------------
        // 3. COMIDA
        // -----------------------------------------------------

        int distanciaAlvo =
            distanciaParaAlvo(
                state,
                me,
                next,
                foodTarget
            );

        score += foodTargetScore(
            state,
            me,
            next,
            foodTarget,
            comportamento
        );

        // -----------------------------------------------------
        // 4. CACA
        // -----------------------------------------------------

        if (comportamento == Comportamento.CACAR) {
            score += huntScore(
                state,
                me,
                enemies,
                next
            );
        }

        // -----------------------------------------------------
        // 5. CENTRO E PAREDE
        // -----------------------------------------------------

        /*
         * Quanto mais perto da comida,
         * menor o peso de centro e parede.
         */
        double multiplier =
            positioningMultiplier(distanciaAlvo);

        /*
         * Cobra pequena quase ignora territorio.
         * Cobra maior passa a valorizar centro.
         */
        double territorial =
            territorialMultiplier(me);

        score += (int) (
            wallScore(state, next)
            * multiplier
            * territorial
        );

        score += (int) (
            centerScore(state, next)
            * multiplier
            * territorial
        );

        return score;
    }

    // ---------------------------------------------------------
    // DISTANCIA ATE O ALVO
    // ---------------------------------------------------------

    private static int distanciaParaAlvo(
        GameState state,
        Snake me,
        Coordinate start,
        Coordinate target
    ) {

        if (target == null) {
            return INFINITO;
        }

        if (same(start, target)) {
            return 0;
        }

        int[][] distances =
            dijkstra(state, start, me);

        return distances
            [target.getY()]
            [target.getX()];
    }

    // ---------------------------------------------------------
    // PESO DO POSICIONAMENTO
    // ---------------------------------------------------------

    private static double positioningMultiplier(
        int distanceToFood
    ) {

        if (distanceToFood <= 2) {
            return 0.10;
        }

        if (distanceToFood <= 4) {
            return 0.35;
        }

        if (distanceToFood <= 6) {
            return 0.65;
        }

        return 1.0;
    }

    // ---------------------------------------------------------
    // PESO TERRITORIAL POR TAMANHO
    // ---------------------------------------------------------

    private static double territorialMultiplier(
        Snake me
    ) {

        // Pequena: foco quase total em crescer.
        if (me.getLength() <= 7) {
            return 0.10;
        }

        // Media: comeca a valorizar territorio.
        if (me.getLength() <= 10) {
            return 0.50;
        }

        // Grande: usa toda a estrategia territorial.
        return 1.0;
    }

    // ---------------------------------------------------------
    // PONTUACAO DA COMIDA
    // ---------------------------------------------------------

    private static int foodTargetScore(
        GameState state,
        Snake me,
        Coordinate next,
        Coordinate target,
        Comportamento comportamento
    ) {

        if (target == null) {
            return 0;
        }

        // Se pode comer agora, recompensa muito alta.
        if (same(next, target)) {
            return 1200;
        }

        int[][] distances =
            dijkstra(state, next, me);

        int distance =
            distances
                [target.getY()]
                [target.getX()];

        if (distance >= INFINITO) {
            return -500;
        }

        int weight;

        if (me.getHealth() <= 20) {
            weight = 100;

        } else if (me.getHealth() <= 40) {
            weight = 70;

        } else if (me.getHealth() <= 60) {
            weight = 45;

        } else if (comportamento == Comportamento.CACAR) {
            weight = 12;

        } else {
            weight = 25;
        }

        return Math.max(
            0,
            20 - distance
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

            if (enemy.getLength() >= me.getLength()) {
                continue;
            }

            List<Coordinate> enemyMoves =
                getPossibleEnemyMoves(
                    state,
                    enemy
                );

            if (enemyMoves.isEmpty()) {
                bestScore = Math.max(
                    bestScore,
                    800
                );
                continue;
            }

            int minDistance = INFINITO;

            for (Coordinate enemyNext : enemyMoves) {

                // Head-to-head favoravel.
                if (
                    same(next, enemyNext) &&
                    me.getLength() > enemy.getLength()
                ) {
                    bestScore = Math.max(
                        bestScore,
                        1200
                    );
                }

                int distance =
                    myDistances
                        [enemyNext.getY()]
                        [enemyNext.getX()];

                minDistance =
                    Math.min(
                        minDistance,
                        distance
                    );
            }

            if (minDistance < INFINITO) {

                int pressure =
                    Math.max(
                        0,
                        15 - minDistance
                    ) * 20;

                // Quanto menos saidas o inimigo tiver, melhor.
                pressure +=
                    (4 - enemyMoves.size()) * 70;

                // Quanto maior nossa vantagem, mais agressiva.
                pressure +=
                    (
                        me.getLength() -
                        enemy.getLength()
                    ) * 15;

                bestScore =
                    Math.max(
                        bestScore,
                        pressure
                    );
            }
        }

        return bestScore;
    }

    // ---------------------------------------------------------
    // CENTRO
    // ---------------------------------------------------------

    private static int centerScore(
        GameState state,
        Coordinate position
    ) {

        int centerX =
            state.getBoard().getWidth() / 2;

        int centerY =
            state.getBoard().getHeight() / 2;

        int distance =
            Math.abs(position.getX() - centerX) +
            Math.abs(position.getY() - centerY);

        return Math.max(
            0,
            10 - distance
        ) * 6;
    }

    // ---------------------------------------------------------
    // PAREDE
    // ---------------------------------------------------------

    private static int wallScore(
        GameState state,
        Coordinate position
    ) {

        int width =
            state.getBoard().getWidth();

        int height =
            state.getBoard().getHeight();

        int left =
            position.getX();

        int right =
            width - 1 - position.getX();

        int down =
            position.getY();

        int up =
            height - 1 - position.getY();

        int nearestWall =
            Math.min(
                Math.min(left, right),
                Math.min(down, up)
            );

        if (nearestWall == 0) {
            return -80;
        }

        if (nearestWall == 1) {
            return -30;
        }

        if (nearestWall == 2) {
            return 10;
        }

        return 25;
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

        Coordinate head =
            me.getHead();

        safeMoves.removeIf(direction -> {

            Coordinate next =
                move(head, direction);

            // Parede.
            if (!insideBoard(state, next)) {
                return true;
            }

            // Corpo.
            if (occupied(state, next)) {
                return true;
            }

            /*
             * Evita head-to-head contra cobra
             * maior ou do mesmo tamanho.
             */
            for (Snake enemy : getEnemies(state)) {

                if (
                    enemy.getLength() <
                    me.getLength()
                ) {
                    continue;
                }

                List<Coordinate> enemyMoves =
                    getPossibleEnemyMoves(
                        state,
                        enemy
                    );

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
    // MOVIMENTOS POSSIVEIS DO ADVERSARIO
    // ---------------------------------------------------------

    private static List<Coordinate> getPossibleEnemyMoves(
        GameState state,
        Snake enemy
    ) {

        List<Coordinate> positions =
            new ArrayList<>();

        Coordinate head =
            enemy.getHead();

        for (
            String direction :
            Arrays.asList(
                "up",
                "down",
                "left",
                "right"
            )
        ) {

            Coordinate next =
                move(head, direction);

            if (!insideBoard(state, next)) {
                continue;
            }

            List<Coordinate> body =
                enemy.getBody();

            // Nao pode voltar pelo pescoco.
            if (
                body != null &&
                body.size() >= 2
            ) {

                Coordinate neck =
                    body.get(1);

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

        int width =
            state.getBoard().getWidth();

        int height =
            state.getBoard().getHeight();

        int[][] distance =
            new int[height][width];

        for (int[] row : distance) {
            Arrays.fill(row, INFINITO);
        }

        PriorityQueue<Node> queue =
            new PriorityQueue<>(
                Comparator.comparingInt(
                    node -> node.distance
                )
            );

        distance
            [start.getY()]
            [start.getX()] = 0;

        queue.add(
            new Node(
                start.getX(),
                start.getY(),
                0
            )
        );

        while (!queue.isEmpty()) {

            Node current =
                queue.poll();

            if (
                current.distance !=
                distance[current.y][current.x]
            ) {
                continue;
            }

            for (int[] direction : DIRECTIONS) {

                int nx =
                    current.x + direction[0];

                int ny =
                    current.y + direction[1];

                Coordinate next =
                    coordinate(nx, ny);

                if (!insideBoard(state, next)) {
                    continue;
                }

                /*
                 * Corpo e obstaculo.
                 * O ponto inicial e permitido.
                 */
                if (
                    occupied(state, next) &&
                    !same(next, start)
                ) {
                    continue;
                }

                int movementCost = 1;

                // Hazard e possivel, mas caro.
                if (isHazard(state, next)) {
                    movementCost += 15;
                }

                /*
                 * Casas que inimigos maiores ou iguais
                 * podem alcancar ficam mais caras.
                 */
                for (
                    Snake enemy :
                    getEnemiesOf(state, snake)
                ) {

                    if (
                        enemy.getLength() <
                        snake.getLength()
                    ) {
                        continue;
                    }

                    for (
                        Coordinate danger :
                        getPossibleEnemyMoves(
                            state,
                            enemy
                        )
                    ) {

                        if (same(next, danger)) {
                            movementCost += 40;
                        }
                    }
                }

                int newDistance =
                    current.distance +
                    movementCost;

                if (
                    newDistance <
                    distance[ny][nx]
                ) {

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

        int width =
            state.getBoard().getWidth();

        int height =
            state.getBoard().getHeight();

        boolean[][] visited =
            new boolean[height][width];

        Queue<Coordinate> queue =
            new ArrayDeque<>();

        queue.add(start);

        visited
            [start.getY()]
            [start.getX()] = true;

        int space = 0;

        while (!queue.isEmpty()) {

            Coordinate current =
                queue.poll();

            space++;

            for (int[] direction : DIRECTIONS) {

                int nx =
                    current.getX() +
                    direction[0];

                int ny =
                    current.getY() +
                    direction[1];

                Coordinate next =
                    coordinate(nx, ny);

                if (!insideBoard(state, next)) {
                    continue;
                }

                if (visited[ny][nx]) {
                    continue;
                }

                if (
                    occupied(state, next) &&
                    !same(next, start)
                ) {
                    continue;
                }

                visited[ny][nx] = true;
                queue.add(next);
            }
        }

        return space;
    }

    // ---------------------------------------------------------
    // MOVIMENTO DE EMERGENCIA
    // ---------------------------------------------------------

    private static String emergencyMove(
        GameState state,
        Snake me
    ) {

        List<String> possible =
            new ArrayList<>();

        for (
            String direction :
            Arrays.asList(
                "up",
                "down",
                "left",
                "right"
            )
        ) {

            Coordinate next =
                move(
                    me.getHead(),
                    direction
                );

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
    // MOVIMENTACAO
    // ---------------------------------------------------------

    private static Coordinate move(
        Coordinate position,
        String direction
    ) {

        int x =
            position.getX();

        int y =
            position.getY();

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

            default:
                break;
        }

        return coordinate(x, y);
    }

    // ---------------------------------------------------------
    // COORDENADA
    // ---------------------------------------------------------

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

    // ---------------------------------------------------------
    // TABULEIRO
    // ---------------------------------------------------------

    private static boolean insideBoard(
        GameState state,
        Coordinate position
    ) {

        return
            position.getX() >= 0 &&
            position.getY() >= 0 &&
            position.getX() <
                state.getBoard().getWidth() &&
            position.getY() <
                state.getBoard().getHeight();
    }

    // ---------------------------------------------------------
    // OCUPACAO
    // ---------------------------------------------------------

    private static boolean occupied(
        GameState state,
        Coordinate position
    ) {

        if (state.getBoard() == null) {
            return false;
        }

        if (
            state.getYou() != null &&
            contains(
                state.getYou().getBody(),
                position
            )
        ) {
            return true;
        }

        List<Snake> snakes =
            state.getBoard().getSnakes();

        if (snakes == null) {
            return false;
        }

        for (Snake snake : snakes) {

            if (
                contains(
                    snake.getBody(),
                    position
                )
            ) {
                return true;
            }
        }

        return false;
    }

    // ---------------------------------------------------------
    // HAZARD
    // ---------------------------------------------------------

    private static boolean isHazard(
        GameState state,
        Coordinate position
    ) {

        return
            state.getBoard().getHazards() != null &&
            contains(
                state.getBoard().getHazards(),
                position
            );
    }

    // ---------------------------------------------------------
    // INIMIGOS
    // ---------------------------------------------------------

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

        if (
            state.getBoard() == null ||
            state.getBoard().getSnakes() == null
        ) {
            return enemies;
        }

        for (
            Snake other :
            state.getBoard().getSnakes()
        ) {

            if (
                snake != null &&
                snake.getId() != null &&
                snake.getId().equals(
                    other.getId()
                )
            ) {
                continue;
            }

            enemies.add(other);
        }

        return enemies;
    }

    // ---------------------------------------------------------
    // LISTA CONTEM COORDENADA
    // ---------------------------------------------------------

    private static boolean contains(
        List<Coordinate> coordinates,
        Coordinate target
    ) {

        if (coordinates == null) {
            return false;
        }

        for (
            Coordinate coordinate :
            coordinates
        ) {

            if (same(coordinate, target)) {
                return true;
            }
        }

        return false;
    }

    // ---------------------------------------------------------
    // COMPARACAO DE COORDENADAS
    // ---------------------------------------------------------

    private static boolean same(
        Coordinate first,
        Coordinate second
    ) {

        return
            first != null &&
            second != null &&
            first.getX() == second.getX() &&
            first.getY() == second.getY();
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