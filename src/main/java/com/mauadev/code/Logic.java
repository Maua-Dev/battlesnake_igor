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

    // Direcoes usadas pelo Dijkstra e Flood Fill.
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
        info.put("color", "#8B0000");
        info.put("head", "beluga");
        info.put("tail", "MLH");

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

        // Se nao existe jogada realmente segura.
        if (safeMoves.isEmpty()) {
            return emergencyMove(state, me);
        }

        Comportamento comportamento =
                escolherComportamento(me, enemies);

        // Calcula uma vez o mapa de distancias de cada inimigo.
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
                            comportamento
                    );

            if (score > bestScore) {

                bestScore = score;

                bestMoves.clear();
                bestMoves.add(direction);

            } else if (score == bestScore) {

                bestMoves.add(direction);
            }
        }

        // Aleatoriedade apenas quando duas jogadas possuem o mesmo valor.
        return bestMoves.get(
                ThreadLocalRandom.current()
                        .nextInt(bestMoves.size())
        );
    }

    // ---------------------------------------------------------
    // ESCOLHA DO COMPORTAMENTO
    // ---------------------------------------------------------

    private static Comportamento escolherComportamento(
            Snake me,
            List<Snake> enemies
    ) {

        // Com pouca vida, procurar comida tem prioridade.
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

        /*
         * Entra em modo de caca apenas com:
         * - tamanho minimo razoavel;
         * - vantagem de pelo menos 2;
         * - vida suficiente.
         */
        if (me.getLength() >= 7
                && me.getLength() >= maiorInimigo + 2
                && me.getHealth() >= 50) {

            return Comportamento.CACAR;
        }

        return Comportamento.ALIMENTAR;
    }

    // ---------------------------------------------------------
    // AVALIACAO DE CADA MOVIMENTO
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

        // -----------------------------------------------------
        // 1. ESPACO LIVRE
        // -----------------------------------------------------

        int space =
                floodFill(state, next);

        /*
         * Ter espaco e uma das prioridades principais.
         * Cada casa acessivel vale 8 pontos.
         */
        score += space * 8;

        /*
         * Se o espaco disponivel for menor ou igual ao nosso
         * proprio tamanho, existe grande risco de aprisionamento.
         */
        if (space <= me.getLength()) {
            score -= 2500;
        }

        // -----------------------------------------------------
        // 2. HAZARDS
        // -----------------------------------------------------

        if (isHazard(state, next)) {
            score -= 300;
        }

        // -----------------------------------------------------
        // 3. COMIDA
        // -----------------------------------------------------

        score += foodScore(
                state,
                me,
                enemies,
                enemyDistances,
                next,
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
        // 5. POSICIONAMENTO
        // -----------------------------------------------------

        /*
         * Parede e centro sao preferencias estrategicas.
         * Nunca possuem peso maior que sobrevivencia.
         */
        score += wallScore(state, next);
        score += centerScore(state, next);

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

        List<Coordinate> foods =
                state.getBoard().getFood();

        if (foods == null || foods.isEmpty()) {
            return 0;
        }

        int[][] myDistances =
                dijkstra(state, start, me);

        int bestFoodDistance = INFINITO;

        boolean encontrouComidaSegura = false;

        for (Coordinate food : foods) {

            int myDistance =
                    myDistances
                            [food.getY()]
                            [food.getX()];

            if (myDistance >= INFINITO) {
                continue;
            }

            /*
             * Ja gastamos um movimento para chegar em start.
             */
            int myArrival = myDistance + 1;

            boolean safeFood = true;

            // Compara nossa chegada com a dos adversarios.
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
                 * Se uma cobra maior ou igual chega antes
                 * ou simultaneamente, nao disputamos.
                 */
                if (enemy.getLength() >= me.getLength()
                        && enemyDistance <= myArrival) {

                    safeFood = false;
                    break;
                }
            }

            if (!safeFood) {
                continue;
            }

            encontrouComidaSegura = true;

            bestFoodDistance =
                    Math.min(
                            bestFoodDistance,
                            myDistance
                    );
        }

        if (!encontrouComidaSegura) {
            return 0;
        }

        int weight;

        /*
         * Quanto menor a vida, maior o peso da comida.
         */
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

            // Nao tenta cacar inimigo maior ou igual.
            if (enemy.getLength() >= me.getLength()) {
                continue;
            }

            List<Coordinate> enemyMoves =
                    getPossibleEnemyMoves(
                            state,
                            enemy
                    );

            /*
             * Inimigo sem movimentos ja esta praticamente morto.
             */
            if (enemyMoves.isEmpty()) {

                bestScore = Math.max(
                        bestScore,
                        800
                );

                continue;
            }

            int minDistance = INFINITO;

            for (Coordinate enemyNext : enemyMoves) {

                /*
                 * Se ambos podem entrar na mesma casa e somos
                 * maiores, a colisao frontal nos favorece.
                 */
                if (same(next, enemyNext)
                        && me.getLength() > enemy.getLength()) {

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

                /*
                 * Quanto menor a distancia das rotas de fuga,
                 * maior a pressao exercida.
                 */
                int pressure =
                        Math.max(
                                0,
                                15 - minDistance
                        ) * 20;

                /*
                 * Inimigo com poucas rotas recebe mais pressao.
                 */
                pressure +=
                        (4 - enemyMoves.size()) * 70;

                /*
                 * Maior vantagem de tamanho permite maior
                 * agressividade.
                 */
                pressure +=
                        (me.getLength()
                                - enemy.getLength()) * 15;

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
    // POSICIONAMENTO CENTRAL
    // ---------------------------------------------------------

    private static int centerScore(
            GameState state,
            Coordinate position
    ) {

        int width =
                state.getBoard().getWidth();

        int height =
                state.getBoard().getHeight();

        int centerX = width / 2;
        int centerY = height / 2;

        /*
         * Distancia Manhattan ate o centro.
         *
         * Em um 11x11:
         * centro = (5,5)
         */
        int distance =
                Math.abs(position.getX() - centerX)
                        + Math.abs(position.getY() - centerY);

        /*
         * Bônus moderado.
         * O centro e desejavel, mas nunca supera
         * sobrevivencia, comida urgente ou espaco.
         */
        return Math.max(
                0,
                10 - distance
        ) * 6;
    }

    // ---------------------------------------------------------
    // PENALIDADE DE PAREDE
    // ---------------------------------------------------------

    private static int wallScore(
            GameState state,
            Coordinate position
    ) {

        int width =
                state.getBoard().getWidth();

        int height =
                state.getBoard().getHeight();

        int distanceLeft =
                position.getX();

        int distanceRight =
                width - 1 - position.getX();

        int distanceDown =
                position.getY();

        int distanceUp =
                height - 1 - position.getY();

        int nearestWall =
                Math.min(
                        Math.min(
                                distanceLeft,
                                distanceRight
                        ),
                        Math.min(
                                distanceDown,
                                distanceUp
                        )
                );

        /*
         * Borda recebe penalidade forte.
         */
        if (nearestWall == 0) {
            return -80;
        }

        /*
         * Uma casa da parede ainda e uma zona
         * relativamente perigosa.
         */
        if (nearestWall == 1) {
            return -30;
        }

        /*
         * Duas casas da parede ja e aceitavel.
         */
        if (nearestWall == 2) {
            return 10;
        }

        // Area interna.
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
                    move(
                            head,
                            direction
                    );

            // Nao sai do tabuleiro.
            if (!insideBoard(state, next)) {
                return true;
            }

            // Nao entra em corpos.
            if (occupied(state, next)) {
                return true;
            }

            /*
             * Evita posicoes onde cobra maior ou igual
             * tambem pode entrar no proximo turno.
             */
            for (Snake enemy : getEnemies(state)) {

                if (enemy.getLength() < me.getLength()) {
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

        for (String direction :
                Arrays.asList(
                        "up",
                        "down",
                        "left",
                        "right"
                )) {

            Coordinate next =
                    move(
                            head,
                            direction
                    );

            if (!insideBoard(state, next)) {
                continue;
            }

            List<Coordinate> body =
                    enemy.getBody();

            /*
             * Impede que o inimigo seja considerado capaz
             * de voltar pelo proprio pescoco.
             */
            if (body != null && body.size() >= 2) {

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

        distance[start.getY()][start.getX()] = 0;

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

            if (current.distance
                    != distance[current.y][current.x]) {

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
                 * Corpos sao obstaculos.
                 * A propria posicao inicial e permitida.
                 */
                if (occupied(state, next)
                        && !same(next, start)) {

                    continue;
                }

                int movementCost = 1;

                /*
                 * Hazard e atravessavel, mas caro.
                 */
                if (isHazard(state, next)) {
                    movementCost += 15;
                }

                /*
                 * Casas que podem ser alcancadas por inimigos
                 * maiores ou iguais recebem custo alto.
                 */
                for (Snake enemy :
                        getEnemiesOf(state, snake)) {

                    if (enemy.getLength()
                            < snake.getLength()) {

                        continue;
                    }

                    for (Coordinate danger :
                            getPossibleEnemyMoves(
                                    state,
                                    enemy
                            )) {

                        if (same(next, danger)) {
                            movementCost += 40;
                        }
                    }
                }

                int newDistance =
                        current.distance
                                + movementCost;

                if (newDistance
                        < distance[ny][nx]) {

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
                        current.getX()
                                + direction[0];

                int ny =
                        current.getY()
                                + direction[1];

                Coordinate next =
                        coordinate(nx, ny);

                if (!insideBoard(state, next)) {
                    continue;
                }

                if (visited[ny][nx]) {
                    continue;
                }

                if (occupied(state, next)
                        && !same(next, start)) {

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

        for (String direction :
                Arrays.asList(
                        "up",
                        "down",
                        "left",
                        "right"
                )) {

            Coordinate next =
                    move(
                            me.getHead(),
                            direction
                    );

            /*
             * Em emergencia pelo menos tenta continuar
             * dentro do tabuleiro.
             */
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

        return position.getX() >= 0
                && position.getY() >= 0
                && position.getX()
                < state.getBoard().getWidth()
                && position.getY()
                < state.getBoard().getHeight();
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

        List<Snake> snakes =
                state.getBoard().getSnakes();

        /*
         * Em alguns testes snakes pode estar vazio,
         * portanto verificamos tambem "you".
         */
        if (state.getYou() != null
                && contains(
                        state.getYou().getBody(),
                        position
                )) {

            return true;
        }

        if (snakes == null) {
            return false;
        }

        for (Snake snake : snakes) {

            if (contains(
                    snake.getBody(),
                    position
            )) {

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

        return state.getBoard().getHazards() != null
                && contains(
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

        if (state.getBoard() == null
                || state.getBoard().getSnakes() == null) {

            return enemies;
        }

        for (Snake other :
                state.getBoard().getSnakes()) {

            if (snake != null
                    && snake.getId() != null
                    && snake.getId()
                    .equals(other.getId())) {

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

        for (Coordinate coordinate :
                coordinates) {

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

        return first != null
                && second != null
                && first.getX() == second.getX()
                && first.getY() == second.getY();
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