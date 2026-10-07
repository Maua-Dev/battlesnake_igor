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
    private static final int PROFUNDIDADE_PREVISAO = 6;

    private static final int[][] DIRECTIONS = {
        {1, 0},
        {-1, 0},
        {0, 1},
        {0, -1}
    };

    private enum Modo {
        CRESCER,
        CONTROLAR,
        SOBREVIVER
    }

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

        Snake me = state.getYou();
        Snake enemy = encontrarPrincipalInimigo(state);

        List<String> safeMoves =
            getSafeMoves(
                state,
                me,
                enemy
            );

        if (safeMoves.isEmpty()) {
            return emergencyMove(state, me);
        }

        Modo modo =
            escolherModo(
                me,
                enemy
            );

        /*
         * Sempre tentamos ter uma fruta como alvo.
         * CONTROLAR nao significa ignorar comida.
         */
        Coordinate foodTarget =
            escolherComidaAlvo(
                state,
                me,
                enemy
            );

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

    // =========================================================
    // MODO
    // =========================================================

    private static Modo escolherModo(
        Snake me,
        Snake enemy
    ) {

        // Vida baixa: comida se torna prioridade máxima.
        if (me.getHealth() <= 40) {
            return Modo.SOBREVIVER;
        }

        /*
         * Cresce até alcançar um tamanho confortável.
         * Depois passa a jogar principalmente por território.
         */
        if (me.getLength() < 10) {
            return Modo.CRESCER;
        }

        return Modo.CONTROLAR;
    }

    // =========================================================
    // AVALIACAO DO MOVIMENTO
    // =========================================================

    private static int avaliarMovimento(
        GameState state,
        Snake me,
        Snake enemy,
        Coordinate next,
        Modo modo,
        Coordinate foodTarget
    ) {

        int score = 0;

        // =====================================================
        // 1. SOBREVIVENCIA
        // =====================================================

        int meuEspaco =
            floodFill(
                state,
                next,
                null
            );

        score += meuEspaco * 10;

        /*
         * Evita entrar em regioes menores que o
         * proprio corpo.
         */
        if (meuEspaco <= me.getLength()) {
            score -= 6000;
        }

        /*
         * Analisa se conseguimos continuar nos
         * proximos turnos sem fechar o proprio corpo.
         */
        score +=
            avaliarFuturoProprio(
                state,
                me,
                next,
                PROFUNDIDADE_PREVISAO
            );

        if (isHazard(state, next)) {
            score -= 700;
        }

        // =====================================================
        // 2. COMIDA
        // =====================================================

        int comidaScore =
            avaliarComida(
                state,
                me,
                next,
                foodTarget,
                modo
            );

        score += comidaScore;

        /*
         * Quando a vida esta baixa, reforca novamente
         * a prioridade da comida.
         */
        if (modo == Modo.SOBREVIVER) {
            score += comidaScore;
        }

        // =====================================================
        // 3. TERRITORIALIDADE
        // =====================================================

        if (enemy != null) {

            score +=
                avaliarTerritorialidade(
                    state,
                    me,
                    enemy,
                    next
                );

            score +=
                avaliarReducaoTerritorial(
                    state,
                    me,
                    enemy,
                    next
                );

            // =================================================
            // 4. ABATE OPORTUNISTA
            // =================================================

            score +=
                avaliarAbate(
                    state,
                    me,
                    enemy,
                    next
                );
        }

        return score;
    }

    // =========================================================
    // COMIDA
    // =========================================================

    private static int avaliarComida(
        GameState state,
        Snake me,
        Coordinate next,
        Coordinate target,
        Modo modo
    ) {

        if (target == null) {
            return 0;
        }

        /*
         * Comer imediatamente vale bastante.
         */
        if (same(next, target)) {

            if (modo == Modo.SOBREVIVER) {
                return 5000;
            }

            if (modo == Modo.CRESCER) {
                return 3500;
            }

            return 2500;
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

            weight = 180;

        } else if (me.getHealth() <= 40) {

            weight = 140;

        } else if (modo == Modo.CRESCER) {

            weight = 110;

        } else {

            /*
             * Mesmo grande e controlando territorio,
             * continua procurando alimento.
             */
            weight = 75;
        }

        return
            Math.max(
                0,
                24 - distance
            ) * weight;
    }

    // =========================================================
    // ESCOLHA DA FRUTA
    // =========================================================

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

        int[][] minhasDistancias =
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

        Coordinate melhor = null;

        int melhorCusto =
            INFINITO;

        for (Coordinate food : foods) {

            int minhaDistancia =
                minhasDistancias
                    [food.getY()]
                    [food.getX()];

            if (minhaDistancia >= INFINITO) {
                continue;
            }

            int custo =
                minhaDistancia;

            /*
             * Antes descartavamos completamente frutas
             * disputadas. Agora apenas aumentamos o custo.
             *
             * Isso evita ignorar comida desnecessariamente.
             */
            if (
                enemy != null &&
                enemyDistances != null
            ) {

                int distanciaInimigo =
                    enemyDistances
                        [food.getY()]
                        [food.getX()];

                if (
                    enemy.getLength() >=
                    me.getLength() &&
                    distanciaInimigo <=
                    minhaDistancia
                ) {

                    custo += 6;
                }

                /*
                 * Se somos maiores e chegamos juntos,
                 * a fruta pode inclusive ser interessante.
                 */
                if (
                    me.getLength() >
                    enemy.getLength() &&
                    distanciaInimigo ==
                    minhaDistancia
                ) {

                    custo -= 2;
                }
            }

            if (custo < melhorCusto) {

                melhorCusto =
                    custo;

                melhor =
                    food;
            }
        }

        return melhor;
    }

    // =========================================================
    // TERRITORIALIDADE
    // =========================================================

    private static int avaliarTerritorialidade(
        GameState state,
        Snake me,
        Snake enemy,
        Coordinate myNext
    ) {

        List<Coordinate> enemyMoves =
            getEnemySurvivingMovesAfterMyMove(
                state,
                me,
                enemy,
                myNext
            );

        /*
         * Se nao existe nenhuma resposta sobrevivente,
         * o inimigo esta praticamente fechado.
         */
        if (enemyMoves.isEmpty()) {
            return 5000;
        }

        int melhorEspacoInimigo = 0;

        /*
         * Sempre consideramos a MELHOR resposta do
         * adversario.
         */
        for (Coordinate enemyNext : enemyMoves) {

            int espaco =
                floodFill(
                    state,
                    enemyNext,
                    myNext
                );

            melhorEspacoInimigo =
                Math.max(
                    melhorEspacoInimigo,
                    espaco
                );
        }

        int total =
            state.getBoard().getWidth() *
            state.getBoard().getHeight();

        /*
         * Quanto menos espaco o adversario possuir,
         * melhor para GreenBeetle.
         */
        return
            (total - melhorEspacoInimigo) * 8;
    }

    // =========================================================
    // REDUCAO GRADUAL DO TERRITORIO
    // =========================================================

    private static int avaliarReducaoTerritorial(
        GameState state,
        Snake me,
        Snake enemy,
        Coordinate myNext
    ) {

        int territorioAtual =
            floodFill(
                state,
                enemy.getHead(),
                null
            );

        List<Coordinate> enemyMoves =
            getEnemySurvivingMovesAfterMyMove(
                state,
                me,
                enemy,
                myNext
            );

        if (enemyMoves.isEmpty()) {
            return 6500;
        }

        int melhorTerritorioFuturo = 0;

        for (Coordinate enemyNext : enemyMoves) {

            int territorio =
                floodFill(
                    state,
                    enemyNext,
                    myNext
                );

            melhorTerritorioFuturo =
                Math.max(
                    melhorTerritorioFuturo,
                    territorio
                );
        }

        int reducao =
            territorioAtual -
            melhorTerritorioFuturo;

        /*
         * Premia fechar gradualmente o mapa.
         */
        return reducao * 140;
    }

    // =========================================================
    // ABATE
    // =========================================================

    private static int avaliarAbate(
        GameState state,
        Snake me,
        Snake enemy,
        Coordinate myNext
    ) {

        int score = 0;

        List<Coordinate> enemyMoves =
            getPossibleEnemyMoves(
                state,
                enemy
            );

        if (enemyMoves.isEmpty()) {
            return 12000;
        }

        // -----------------------------------------------------
        // HEAD TO HEAD FAVORAVEL
        // -----------------------------------------------------

        if (
            me.getLength() >
            enemy.getLength()
        ) {

            for (Coordinate enemyNext : enemyMoves) {

                if (
                    same(
                        myNext,
                        enemyNext
                    )
                ) {

                    /*
                     * Nao significa que o inimigo ira
                     * necessariamente escolher essa casa,
                     * mas podemos controlar a regiao.
                     */
                    score += 4500;
                    break;
                }
            }
        }

        // -----------------------------------------------------
        // VERIFICA SE TODAS AS ROTAS SAO FATAIS
        // -----------------------------------------------------

        List<Coordinate> survivingMoves =
            getEnemySurvivingMovesAfterMyMove(
                state,
                me,
                enemy,
                myNext
            );

        if (survivingMoves.isEmpty()) {
            return score + 12000;
        }

        boolean todasFatais = true;

        for (Coordinate enemyNext : survivingMoves) {

            int space =
                floodFill(
                    state,
                    enemyNext,
                    myNext
                );

            /*
             * Se existe pelo menos uma regiao grande
             * o suficiente, ainda nao consideramos
             * morte garantida.
             */
            if (space > enemy.getLength()) {

                todasFatais = false;
                break;
            }
        }

        if (todasFatais) {
            score += 9000;
        }

        return score;
    }

    // =========================================================
    // MOVIMENTOS SEGUROS
    // =========================================================

    private static List<String> getSafeMoves(
        GameState state,
        Snake me,
        Snake enemy
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

            // Parede.
            if (!insideBoard(state, next)) {
                return true;
            }

            // Corpo.
            if (occupied(state, next)) {
                return true;
            }

            if (enemy == null) {
                return false;
            }

            /*
             * Evita qualquer head-to-head que possa
             * resultar em derrota ou empate.
             */
            List<Coordinate> enemyMoves =
                getPossibleEnemyMoves(
                    state,
                    enemy
                );

            for (Coordinate enemyNext : enemyMoves) {

                if (
                    same(
                        next,
                        enemyNext
                    ) &&
                    me.getLength() <=
                    enemy.getLength()
                ) {

                    return true;
                }
            }

            return false;
        });

        return safeMoves;
    }

    // =========================================================
    // MOVIMENTOS DO INIMIGO
    // =========================================================

    private static List<Coordinate> getPossibleEnemyMoves(
        GameState state,
        Snake enemy
    ) {

        List<Coordinate> moves =
            new ArrayList<>();

        if (enemy == null) {
            return moves;
        }

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

            /*
             * Evita voltar diretamente para o pescoco.
             */
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

            if (occupied(state, next)) {
                continue;
            }

            moves.add(next);
        }

        return moves;
    }

    /*
     * Retorna apenas respostas nas quais o inimigo
     * continua vivo depois da nossa jogada.
     */
    private static List<Coordinate> getEnemySurvivingMovesAfterMyMove(
        GameState state,
        Snake me,
        Snake enemy,
        Coordinate myNext
    ) {

        List<Coordinate> surviving =
            new ArrayList<>();

        List<Coordinate> enemyMoves =
            getPossibleEnemyMoves(
                state,
                enemy
            );

        for (Coordinate enemyNext : enemyMoves) {

            /*
             * Head-to-head.
             */
            if (same(enemyNext, myNext)) {

                if (
                    me.getLength() >
                    enemy.getLength()
                ) {

                    // O inimigo morreria.
                    continue;
                }

                /*
                 * Se ele for igual ou maior,
                 * nossa jogada nao deveria ter passado
                 * pelo getSafeMoves.
                 */
                surviving.add(enemyNext);
                continue;
            }

            surviving.add(enemyNext);
        }

        return surviving;
    }

    // =========================================================
    // FUTURO DO PROPRIO CORPO
    // =========================================================

    private static int avaliarFuturoProprio(
        GameState state,
        Snake me,
        Coordinate firstMove,
        int profundidade
    ) {

        List<Coordinate> corpo =
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
                corpo,
                firstMove,
                comeu
            );

        if (!corpoValido(corpoSimulado)) {
            return -10000;
        }

        int score = 0;

        int saidas =
            contarSaidasFuturas(
                state,
                corpoSimulado,
                firstMove
            );

        if (saidas == 0) {
            return -10000;
        }

        if (saidas == 1) {

            score -= 2500;

        } else if (saidas == 2) {

            score -= 350;

        } else {

            score += 200;
        }

        int profundidadeAlcancada =
            preverSobrevivencia(
                state,
                corpoSimulado,
                profundidade - 1
            );

        if (
            profundidadeAlcancada <
            profundidade - 1
        ) {

            score -=
                3000 -
                profundidadeAlcancada * 300;

        } else {

            score +=
                profundidadeAlcancada * 150;
        }

        return score;
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

        int melhor = -1;

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

            boolean comeu =
                contains(
                    state.getBoard().getFood(),
                    next
                );

            /*
             * Se nao comer, a cauda se move e pode
             * ser considerada livre.
             */
            if (
                colisaoComCorpoSimulado(
                    corpo,
                    next,
                    !comeu
                )
            ) {
                continue;
            }

            if (
                ocupadoPorInimigo(
                    state,
                    next
                )
            ) {
                continue;
            }

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

            melhor =
                Math.max(
                    melhor,
                    resultado
                );
        }

        if (melhor < 0) {
            return 0;
        }

        return 1 + melhor;
    }

    // =========================================================
    // SAIDAS FUTURAS
    // =========================================================

    private static int contarSaidasFuturas(
        GameState state,
        List<Coordinate> corpo,
        Coordinate head
    ) {

        int saidas = 0;

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

            boolean comeu =
                contains(
                    state.getBoard().getFood(),
                    next
                );

            if (
                colisaoComCorpoSimulado(
                    corpo,
                    next,
                    !comeu
                )
            ) {
                continue;
            }

            if (
                ocupadoPorInimigo(
                    state,
                    next
                )
            ) {
                continue;
            }

            saidas++;
        }

        return saidas;
    }

    // =========================================================
    // SIMULACAO DO CORPO
    // =========================================================

    private static List<Coordinate> moverCorpoSimulado(
        List<Coordinate> corpo,
        Coordinate novaCabeca,
        boolean comeu
    ) {

        List<Coordinate> novo =
            copiarCorpo(corpo);

        novo.add(
            0,
            coordinate(
                novaCabeca.getX(),
                novaCabeca.getY()
            )
        );

        if (
            !comeu &&
            !novo.isEmpty()
        ) {
            novo.remove(
                novo.size() - 1
            );
        }

        return novo;
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
        Coordinate position,
        boolean caudaVaiMover
    ) {

        if (
            corpo == null ||
            corpo.isEmpty()
        ) {
            return false;
        }

        int limite =
            corpo.size();

        if (caudaVaiMover) {
            limite--;
        }

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

        for (
            int i = 0;
            i < corpo.size();
            i++
        ) {

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

    // =========================================================
    // DIJKSTRA
    // =========================================================

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
                    coordinate(
                        nx,
                        ny
                    );

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
                    current.distance +
                    cost;

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

    // =========================================================
    // FLOOD FILL
    // =========================================================

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
                    same(
                        next,
                        extraBlocked
                    )
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

    // =========================================================
    // INIMIGO
    // =========================================================

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

    // =========================================================
    // OCUPACAO
    // =========================================================

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

    // =========================================================
    // MOVIMENTO
    // =========================================================

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

        return coordinate(
            x,
            y
        );
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

    // =========================================================
    // AUXILIARES
    // =========================================================

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
            first.getX() ==
                second.getX() &&
            first.getY() ==
                second.getY();
    }

    // =========================================================
    // NODE
    // =========================================================

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