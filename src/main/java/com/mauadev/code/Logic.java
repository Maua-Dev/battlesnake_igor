package com.mauadev.code;

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

public class Logic {

    private static final int INFINITO = 1_000_000;

    // Quantos turnos a cobra tenta prever.
    private static final int PROFUNDIDADE_PREVISAO = 6;

    private static final int[][] DIRECTIONS = {
        {1, 0},
        {-1, 0},
        {0, 1},
        {0, -1}
    };

    private enum Modo {
        CRESCER,
        CACAR,
        SOBREVIVER
    }

    // ---------------------------------------------------------
    // INFORMACOES
    // ---------------------------------------------------------

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

    // ---------------------------------------------------------
    // DECISAO PRINCIPAL
    // ---------------------------------------------------------

    public static String getMove(GameState state) {

        Snake me = state.getYou();
        Snake enemy = encontrarPrincipalInimigo(state);

        List<String> safeMoves =
            getSafeMoves(state, me);

        if (safeMoves.isEmpty()) {
            return emergencyMove(state, me);
        }

        Modo modo =
            escolherModo(me, enemy);

        Coordinate foodTarget = null;

        // Em modo caça para de buscar comida propositalmente.
        if (
            modo == Modo.CRESCER ||
            modo == Modo.SOBREVIVER
        ) {
            foodTarget =
                escolherComidaAlvo(
                    state,
                    me,
                    enemy
                );
        }

        String melhorMove =
            safeMoves.get(0);

        int melhorScore =
            Integer.MIN_VALUE;

        for (String direction : safeMoves) {

            Coordinate next =
                move(
                    me.getHead(),
                    direction
                );

            int score =
                avaliarMovimento(
                    state,
                    me,
                    enemy,
                    next,
                    modo,
                    foodTarget
                );

            if (score > melhorScore) {
                melhorScore = score;
                melhorMove = direction;
            }
        }

        return melhorMove;
    }

    // ---------------------------------------------------------
    // ESCOLHA DO MODO
    // ---------------------------------------------------------

    private static Modo escolherModo(
        Snake me,
        Snake enemy
    ) {

        // Vida baixa quebra o modo de caça.
        if (me.getHealth() <= 35) {
            return Modo.SOBREVIVER;
        }

        if (enemy == null) {
            return Modo.CRESCER;
        }

        int ideal =
            tamanhoIdealDeCaca(enemy);

        /*
         * So caca quando:
         * - atingiu tamanho ideal;
         * - possui vantagem de tamanho.
         */
        if (
            me.getLength() >= ideal &&
            me.getLength() > enemy.getLength()
        ) {
            return Modo.CACAR;
        }

        return Modo.CRESCER;
    }

    // ---------------------------------------------------------
    // TAMANHO IDEAL DE CACA
    // ---------------------------------------------------------

    private static int tamanhoIdealDeCaca(
        Snake enemy
    ) {

        int ideal =
            enemy.getLength() + 2;

        // Nunca começa a caça muito pequena.
        ideal = Math.max(8, ideal);

        // Evita crescer demais em tabuleiro 11x11.
        ideal = Math.min(11, ideal);

        return ideal;
    }

    // ---------------------------------------------------------
    // AVALIACAO DO MOVIMENTO
    // ---------------------------------------------------------

    private static int avaliarMovimento(
        GameState state,
        Snake me,
        Snake enemy,
        Coordinate next,
        Modo modo,
        Coordinate foodTarget
    ) {

        int score = 0;

        // -----------------------------------------------------
        // 1. ESPACO ATUAL
        // -----------------------------------------------------

        int meuEspaco =
            floodFill(
                state,
                next,
                null
            );

        score += meuEspaco * 7;

        if (meuEspaco <= me.getLength()) {
            score -= 3000;
        }

        // -----------------------------------------------------
        // 2. PREVISAO DO PROPRIO CORPO
        // -----------------------------------------------------

        int futuro =
            avaliarFuturoProprio(
                state,
                me,
                next,
                PROFUNDIDADE_PREVISAO
            );

        score += futuro;

        // -----------------------------------------------------
        // 3. HAZARD
        // -----------------------------------------------------

        if (isHazard(state, next)) {
            score -= 400;
        }

        // -----------------------------------------------------
        // 4. CRESCIMENTO
        // -----------------------------------------------------

        if (
            modo == Modo.CRESCER ||
            modo == Modo.SOBREVIVER
        ) {

            score += avaliarComida(
                state,
                me,
                next,
                foodTarget
            );

            /*
             * Enquanto cresce, territorio e secundario.
             */
            score +=
                centerScore(state, next) / 4;

            score +=
                wallScore(state, next) / 4;
        }

        // -----------------------------------------------------
        // 5. CACA
        // -----------------------------------------------------

        if (
            modo == Modo.CACAR &&
            enemy != null
        ) {

            score += avaliarCaca(
                state,
                me,
                enemy,
                next
            );

            /*
             * Evita comida desnecessaria durante a caça.
             */
            if (
                contains(
                    state.getBoard().getFood(),
                    next
                )
            ) {
                score -= 500;
            }

            // Durante a caça, controle territorial importa.
            score +=
                centerScore(state, next);

            score +=
                wallScore(state, next);
        }

        return score;
    }

    // ---------------------------------------------------------
    // PREVISAO DO PROPRIO CAMINHO
    // ---------------------------------------------------------

    private static int avaliarFuturoProprio(
        GameState state,
        Snake me,
        Coordinate firstMove,
        int profundidade
    ) {

        List<Coordinate> corpoAtual =
            copiarCorpo(
                me.getBody()
            );

        boolean comeu =
            contains(
                state.getBoard().getFood(),
                firstMove
            );

        List<Coordinate> corpoSimulado =
            moverCorpoSimulado(
                corpoAtual,
                firstMove,
                comeu
            );

        if (!corpoValido(corpoSimulado)) {
            return -10000;
        }

        int profundidadeAlcancada =
            preverSobrevivencia(
                state,
                corpoSimulado,
                profundidade - 1
            );

        /*
         * Se nao existe caminho seguro suficiente,
         * a jogada provavelmente vai fechar a cobra.
         */
        if (
            profundidadeAlcancada <
            profundidade - 1
        ) {

            return
                -2500 +
                profundidadeAlcancada * 250;
        }

        return
            profundidadeAlcancada * 120;
    }

    private static int preverSobrevivencia(
        GameState state,
        List<Coordinate> corpo,
        int profundidade
    ) {

        if (profundidade <= 0) {
            return 0;
        }

        Coordinate head =
            corpo.get(0);

        int melhorProfundidade = -1;
        int movimentosPossiveis = 0;

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
                    head,
                    direction
                );

            if (!insideBoard(state, next)) {
                continue;
            }

            /*
             * Verifica colisao com o proprio
             * corpo simulado.
             */
            if (
                colisaoComCorpoSimulado(
                    corpo,
                    next
                )
            ) {
                continue;
            }

            // Evita corpo dos adversarios.
            if (
                ocupadoPorInimigo(
                    state,
                    next
                )
            ) {
                continue;
            }

            movimentosPossiveis++;

            boolean comeu =
                contains(
                    state.getBoard().getFood(),
                    next
                );

            List<Coordinate> novoCorpo =
                moverCorpoSimulado(
                    corpo,
                    next,
                    comeu
                );

            if (!corpoValido(novoCorpo)) {
                continue;
            }

            int resultado =
                preverSobrevivencia(
                    state,
                    novoCorpo,
                    profundidade - 1
                );

            melhorProfundidade =
                Math.max(
                    melhorProfundidade,
                    resultado
                );
        }

        if (movimentosPossiveis == 0) {
            return 0;
        }

        return
            1 +
            Math.max(
                0,
                melhorProfundidade
            );
    }

    // ---------------------------------------------------------
    // SIMULACAO DO CORPO
    // ---------------------------------------------------------

    private static List<Coordinate> moverCorpoSimulado(
        List<Coordinate> corpo,
        Coordinate novaCabeca,
        boolean comeu
    ) {

        List<Coordinate> novoCorpo =
            copiarCorpo(corpo);

        novoCorpo.add(
            0,
            coordinate(
                novaCabeca.getX(),
                novaCabeca.getY()
            )
        );

        /*
         * Se nao comeu, a cauda anda.
         */
        if (
            !comeu &&
            !novoCorpo.isEmpty()
        ) {

            novoCorpo.remove(
                novoCorpo.size() - 1
            );
        }

        return novoCorpo;
    }

    private static List<Coordinate> copiarCorpo(
        List<Coordinate> corpo
    ) {

        List<Coordinate> copia =
            new ArrayList<>();

        if (corpo == null) {
            return copia;
        }

        for (Coordinate parte : corpo) {

            copia.add(
                coordinate(
                    parte.getX(),
                    parte.getY()
                )
            );
        }

        return copia;
    }

    private static boolean colisaoComCorpoSimulado(
        List<Coordinate> corpo,
        Coordinate position
    ) {

        if (
            corpo == null ||
            corpo.isEmpty()
        ) {
            return false;
        }

        /*
         * A ultima posicao normalmente sera liberada
         * porque a cauda se move.
         */
        int limite =
            corpo.size() - 1;

        for (int i = 0; i < limite; i++) {

            if (
                same(
                    corpo.get(i),
                    position
                )
            ) {
                return true;
            }
        }

        return false;
    }

    private static boolean corpoValido(
        List<Coordinate> corpo
    ) {

        for (int i = 0; i < corpo.size(); i++) {

            for (
                int j = i + 1;
                j < corpo.size();
                j++
            ) {

                if (
                    same(
                        corpo.get(i),
                        corpo.get(j)
                    )
                ) {
                    return false;
                }
            }
        }

        return true;
    }

    // ---------------------------------------------------------
    // CACA
    // ---------------------------------------------------------

    private static int avaliarCaca(
        GameState state,
        Snake me,
        Snake enemy,
        Coordinate myNext
    ) {

        int score = 0;

        List<Coordinate> enemyMoves =
            getPossibleEnemyMoves(
                state,
                enemy,
                myNext,
                me
            );

        // Inimigo sem saida.
        if (enemyMoves.isEmpty()) {
            return 5000;
        }

        // -----------------------------------------------------
        // REDUZIR OPCOES DO INIMIGO
        // -----------------------------------------------------

        if (enemyMoves.size() == 1) {
            score += 1800;

        } else if (enemyMoves.size() == 2) {
            score += 700;

        } else if (enemyMoves.size() == 3) {
            score += 200;
        }

        // -----------------------------------------------------
        // MELHOR FUGA DO INIMIGO
        // -----------------------------------------------------

        int maiorEspacoInimigo = 0;

        for (Coordinate enemyNext : enemyMoves) {

            int enemySpace =
                floodFill(
                    state,
                    enemyNext,
                    myNext
                );

            maiorEspacoInimigo =
                Math.max(
                    maiorEspacoInimigo,
                    enemySpace
                );

            // Head-to-head favoravel.
            if (
                same(myNext, enemyNext) &&
                me.getLength() >
                enemy.getLength()
            ) {
                score += 2000;
            }
        }

        /*
         * Assume que o inimigo escolhe a
         * melhor fuga possivel.
         */
        score +=
            (121 - maiorEspacoInimigo) * 10;

        // -----------------------------------------------------
        // DISTANCIA DAS ROTAS DE FUGA
        // -----------------------------------------------------

        int[][] distances =
            dijkstra(
                state,
                myNext
            );

        int menorDistancia =
            INFINITO;

        for (Coordinate enemyNext : enemyMoves) {

            int distance =
                distances
                    [enemyNext.getY()]
                    [enemyNext.getX()];

            menorDistancia =
                Math.min(
                    menorDistancia,
                    distance
                );
        }

        if (menorDistancia < INFINITO) {

            score +=
                Math.max(
                    0,
                    12 - menorDistancia
                ) * 25;
        }

        // -----------------------------------------------------
        // PRESSIONAR CONTRA A PAREDE
        // -----------------------------------------------------

        int wallDistance =
            distanciaParede(
                state,
                enemy.getHead()
            );

        if (wallDistance == 0) {
            score += 350;

        } else if (wallDistance == 1) {
            score += 180;
        }

        // Vantagem de tamanho.
        score +=
            (
                me.getLength() -
                enemy.getLength()
            ) * 40;

        return score;
    }

    // ---------------------------------------------------------
    // COMIDA
    // ---------------------------------------------------------

    private static int avaliarComida(
        GameState state,
        Snake me,
        Coordinate next,
        Coordinate target
    ) {

        if (target == null) {
            return 0;
        }

        if (same(next, target)) {
            return 1500;
        }

        int[][] distances =
            dijkstra(
                state,
                next
            );

        int distance =
            distances
                [target.getY()]
                [target.getX()];

        if (distance >= INFINITO) {
            return -500;
        }

        int weight;

        if (me.getHealth() <= 20) {
            weight = 120;

        } else if (me.getHealth() <= 35) {
            weight = 80;

        } else {
            weight = 45;
        }

        return
            Math.max(
                0,
                20 - distance
            ) * weight;
    }

    // ---------------------------------------------------------
    // ESCOLHA DA COMIDA
    // ---------------------------------------------------------

    private static Coordinate escolherComidaAlvo(
        GameState state,
        Snake me,
        Snake enemy
    ) {

        List<Coordinate> foods =
            state.getBoard().getFood();

        if (
            foods == null ||
            foods.isEmpty()
        ) {
            return null;
        }

        int[][] myDistances =
            dijkstra(
                state,
                me.getHead()
            );

        int[][] enemyDistances =
            enemy == null
                ? null
                : dijkstra(
                    state,
                    enemy.getHead()
                );

        Coordinate best = null;
        int bestDistance = INFINITO;

        for (Coordinate food : foods) {

            int myDistance =
                myDistances
                    [food.getY()]
                    [food.getX()];

            if (myDistance >= INFINITO) {
                continue;
            }

            if (enemy != null) {

                int enemyDistance =
                    enemyDistances
                        [food.getY()]
                        [food.getX()];

                /*
                 * Evita disputar com cobra maior
                 * ou igual que chega primeiro.
                 */
                if (
                    enemy.getLength() >= me.getLength() &&
                    enemyDistance <= myDistance
                ) {
                    continue;
                }
            }

            if (myDistance < bestDistance) {
                bestDistance = myDistance;
                best = food;
            }
        }

        return best;
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

            if (!insideBoard(state, next)) {
                return true;
            }

            if (occupied(state, next)) {
                return true;
            }

            Snake enemy =
                encontrarPrincipalInimigo(state);

            if (enemy == null) {
                return false;
            }

            /*
             * Evita head-to-head desfavoravel.
             */
            if (
                enemy.getLength() >=
                me.getLength()
            ) {

                List<Coordinate> enemyMoves =
                    getPossibleEnemyMoves(
                        state,
                        enemy,
                        null,
                        me
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
    // POSSIVEIS MOVIMENTOS DO INIMIGO
    // ---------------------------------------------------------

    private static List<Coordinate> getPossibleEnemyMoves(
        GameState state,
        Snake enemy,
        Coordinate extraBlocked,
        Snake me
    ) {

        List<Coordinate> moves =
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
                    enemy.getHead(),
                    direction
                );

            if (!insideBoard(state, next)) {
                continue;
            }

            List<Coordinate> body =
                enemy.getBody();

            if (
                body != null &&
                body.size() >= 2 &&
                same(
                    next,
                    body.get(1)
                )
            ) {
                continue;
            }

            /*
             * Nossa futura cabeca pode bloquear
             * uma rota se somos maiores.
             */
            if (
                extraBlocked != null &&
                same(next, extraBlocked)
            ) {

                if (
                    me != null &&
                    me.getLength() >
                    enemy.getLength()
                ) {
                    continue;
                }
            }

            if (occupied(state, next)) {
                continue;
            }

            moves.add(next);
        }

        return moves;
    }

    // ---------------------------------------------------------
    // DIJKSTRA
    // ---------------------------------------------------------

    private static int[][] dijkstra(
        GameState state,
        Coordinate start
    ) {

        int width =
            state.getBoard().getWidth();

        int height =
            state.getBoard().getHeight();

        int[][] distance =
            new int[height][width];

        for (int[] row : distance) {
            Arrays.fill(
                row,
                INFINITO
            );
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
                    current.x +
                    direction[0];

                int ny =
                    current.y +
                    direction[1];

                Coordinate next =
                    coordinate(nx, ny);

                if (!insideBoard(state, next)) {
                    continue;
                }

                if (
                    occupied(state, next) &&
                    !same(next, start)
                ) {
                    continue;
                }

                int cost = 1;

                if (isHazard(state, next)) {
                    cost += 15;
                }

                int newDistance =
                    current.distance + cost;

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
        Coordinate start,
        Coordinate extraBlocked
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

                Coordinate next =
                    coordinate(
                        current.getX() +
                            direction[0],
                        current.getY() +
                            direction[1]
                    );

                if (!insideBoard(state, next)) {
                    continue;
                }

                if (
                    visited
                        [next.getY()]
                        [next.getX()]
                ) {
                    continue;
                }

                if (
                    extraBlocked != null &&
                    same(next, extraBlocked)
                ) {
                    continue;
                }

                if (
                    occupied(state, next) &&
                    !same(next, start)
                ) {
                    continue;
                }

                visited
                    [next.getY()]
                    [next.getX()] = true;

                queue.add(next);
            }
        }

        return space;
    }

    // ---------------------------------------------------------
    // POSICIONAMENTO
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
            Math.abs(
                position.getX() - centerX
            ) +
            Math.abs(
                position.getY() - centerY
            );

        return
            Math.max(
                0,
                10 - distance
            ) * 5;
    }

    private static int wallScore(
        GameState state,
        Coordinate position
    ) {

        int distance =
            distanciaParede(
                state,
                position
            );

        if (distance == 0) {
            return -80;
        }

        if (distance == 1) {
            return -25;
        }

        if (distance == 2) {
            return 10;
        }

        return 20;
    }

    private static int distanciaParede(
        GameState state,
        Coordinate position
    ) {

        int left =
            position.getX();

        int right =
            state.getBoard().getWidth()
            - 1
            - position.getX();

        int down =
            position.getY();

        int up =
            state.getBoard().getHeight()
            - 1
            - position.getY();

        return
            Math.min(
                Math.min(left, right),
                Math.min(down, up)
            );
    }

    // ---------------------------------------------------------
    // INIMIGO
    // ---------------------------------------------------------

    private static Snake encontrarPrincipalInimigo(
        GameState state
    ) {

        if (
            state.getBoard() == null ||
            state.getBoard().getSnakes() == null
        ) {
            return null;
        }

        Snake me =
            state.getYou();

        Snake target = null;

        for (
            Snake snake :
            state.getBoard().getSnakes()
        ) {

            if (
                me.getId() != null &&
                me.getId().equals(
                    snake.getId()
                )
            ) {
                continue;
            }

            /*
             * Em 1x1 sera o unico adversario.
             * Com varios, prioriza o menor.
             */
            if (
                target == null ||
                snake.getLength() <
                target.getLength()
            ) {
                target = snake;
            }
        }

        return target;
    }

    // ---------------------------------------------------------
    // OCUPACAO
    // ---------------------------------------------------------

    private static boolean occupied(
        GameState state,
        Coordinate position
    ) {

        if (
            state.getYou() != null &&
            contains(
                state.getYou().getBody(),
                position
            )
        ) {
            return true;
        }

        if (
            state.getBoard() == null ||
            state.getBoard().getSnakes() == null
        ) {
            return false;
        }

        for (
            Snake snake :
            state.getBoard().getSnakes()
        ) {

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

    private static boolean ocupadoPorInimigo(
        GameState state,
        Coordinate position
    ) {

        if (
            state.getBoard() == null ||
            state.getBoard().getSnakes() == null
        ) {
            return false;
        }

        Snake me =
            state.getYou();

        for (
            Snake snake :
            state.getBoard().getSnakes()
        ) {

            if (
                me != null &&
                me.getId() != null &&
                me.getId().equals(
                    snake.getId()
                )
            ) {
                continue;
            }

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
    // MOVIMENTO
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

    private static String emergencyMove(
        GameState state,
        Snake me
    ) {

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

            if (
                insideBoard(state, next) &&
                !occupied(state, next)
            ) {
                return direction;
            }
        }

        return "up";
    }

    // ---------------------------------------------------------
    // AUXILIARES
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

            if (
                same(
                    coordinate,
                    target
                )
            ) {
                return true;
            }
        }

        return false;
    }

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