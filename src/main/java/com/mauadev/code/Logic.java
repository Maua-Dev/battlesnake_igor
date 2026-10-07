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
    private static final int PROFUNDIDADE_CACA = 3;

    private static final int[][] DIRECTIONS = {
        {1, 0},
        {-1, 0},
        {0, 1},
        {0, -1}
    };

    private enum Modo {
        CRESCER,
        CONTROLAR,
        CACAR,
        SOBREVIVER
    }

    // =========================================================
    // INFORMACOES
    // =========================================================

    public static Map<String, String> info() {
        Map<String, String> info = new HashMap<>();

        info.put("apiversion", "1");
        info.put("author", "");
        info.put("color", "#eeff00");
        info.put("head", "snow-worm");
        info.put("tail", "nr-booster");

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
            getSafeMoves(state, me, enemy);

        if (safeMoves.isEmpty()) {
            return emergencyMove(state, me);
        }

        Modo modo =
            escolherModo(me, enemy);

        /*
         * Em caça, evita comida se houver opção segura
         * sem crescimento.
         */
        if (modo == Modo.CACAR) {

            List<String> semComida =
                new ArrayList<>();

            for (String direction : safeMoves) {

                Coordinate next =
                    move(
                        me.getHead(),
                        direction
                    );

                if (
                    !contains(
                        state.getBoard().getFood(),
                        next
                    )
                ) {
                    semComida.add(direction);
                }
            }

            if (!semComida.isEmpty()) {
                safeMoves = semComida;
            }
        }

        Coordinate foodTarget = null;

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

    // =========================================================
    // MODO
    // =========================================================

    private static Modo escolherModo(
        Snake me,
        Snake enemy
    ) {

        if (me.getHealth() <= 35) {
            return Modo.SOBREVIVER;
        }

        if (enemy == null) {
            return Modo.CRESCER;
        }

        /*
         * Caça somente com vantagem real.
         */
        if (
            me.getLength() >= 8 &&
            me.getLength() >= enemy.getLength() + 2
        ) {
            return Modo.CACAR;
        }

        /*
         * Já cresceu bastante mas ainda não
         * tem vantagem suficiente.
         */
        if (me.getLength() >= 11) {
            return Modo.CONTROLAR;
        }

        return Modo.CRESCER;
    }

    // =========================================================
    // AVALIACAO PRINCIPAL
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

        // -----------------------------------------------------
        // 1. ESPACO
        // -----------------------------------------------------

        int space =
            floodFill(
                state,
                next,
                null
            );

        score += space * 8;

        if (space <= me.getLength()) {
            score -= 4000;
        }

        // -----------------------------------------------------
        // 2. PREVISAO DO PROPRIO CORPO
        // -----------------------------------------------------

        score +=
            avaliarFuturoProprio(
                state,
                me,
                next,
                PROFUNDIDADE_PREVISAO
            );

        // -----------------------------------------------------
        // 3. MAPA DE CONTROLE
        // -----------------------------------------------------

        if (enemy != null) {

            score +=
                avaliarControleTerritorial(
                    state,
                    me,
                    enemy,
                    next
                );
        }

        // -----------------------------------------------------
        // 4. HAZARD
        // -----------------------------------------------------

        if (isHazard(state, next)) {
            score -= 500;
        }

        // -----------------------------------------------------
        // 5. CRESCER
        // -----------------------------------------------------

        if (modo == Modo.CRESCER) {

            score +=
                avaliarComida(
                    state,
                    me,
                    next,
                    foodTarget
                );

            score +=
                centerScore(state, next) / 4;

            score +=
                wallScore(state, next) / 4;
        }

        // -----------------------------------------------------
        // 6. SOBREVIVER
        // -----------------------------------------------------

        if (modo == Modo.SOBREVIVER) {

            score +=
                avaliarComida(
                    state,
                    me,
                    next,
                    foodTarget
                ) * 2;

            score +=
                centerScore(state, next) / 5;
        }

        // -----------------------------------------------------
        // 7. CONTROLAR
        // -----------------------------------------------------

        if (modo == Modo.CONTROLAR) {

            score +=
                centerScore(state, next);

            score +=
                wallScore(state, next);

            score +=
                contarSaidasImediatas(
                    state,
                    me,
                    next
                ) * 150;

            /*
             * Cobra grande evita crescer sem necessidade.
             */
            if (
                contains(
                    state.getBoard().getFood(),
                    next
                )
            ) {
                score -= 800;
            }

            /*
             * Se somos menores, evitamos aproximar
             * demais a cabeça adversária.
             */
            if (
                enemy != null &&
                enemy.getLength() >= me.getLength()
            ) {

                int distancia =
                    distanciaManhattan(
                        next,
                        enemy.getHead()
                    );

                if (distancia == 1) {
                    score -= 1200;

                } else if (distancia == 2) {
                    score -= 400;
                }
            }
        }

        // -----------------------------------------------------
        // 8. CACAR
        // -----------------------------------------------------

        if (
            modo == Modo.CACAR &&
            enemy != null
        ) {

            score +=
                avaliarCaca(
                    state,
                    me,
                    enemy,
                    next
                );

            score +=
                centerScore(state, next);

            score +=
                wallScore(state, next);

            score +=
                contarSaidasImediatas(
                    state,
                    me,
                    next
                ) * 100;
        }

        return score;
    }

    // =========================================================
    // MAPA DE CONTROLE
    // =========================================================

    private static int avaliarControleTerritorial(
        GameState state,
        Snake me,
        Snake enemy,
        Coordinate myNext
    ) {

        int score = 0;

        int[][] myDistances =
            dijkstra(
                state,
                myNext
            );

        int[][] enemyDistances =
            dijkstra(
                state,
                enemy.getHead()
            );

        int width =
            state.getBoard().getWidth();

        int height =
            state.getBoard().getHeight();

        int minhasCasas = 0;
        int casasInimigo = 0;
        int disputadas = 0;

        for (int y = 0; y < height; y++) {

            for (int x = 0; x < width; x++) {

                int minha =
                    myDistances[y][x];

                int dele =
                    enemyDistances[y][x];

                if (
                    minha >= INFINITO &&
                    dele >= INFINITO
                ) {
                    continue;
                }

                if (minha < dele) {

                    minhasCasas++;

                } else if (dele < minha) {

                    casasInimigo++;

                } else {

                    /*
                     * Ambos chegam juntos.
                     */
                    if (
                        me.getLength() >
                        enemy.getLength()
                    ) {

                        minhasCasas++;

                    } else if (
                        me.getLength() <
                        enemy.getLength()
                    ) {

                        casasInimigo++;

                    } else {

                        disputadas++;
                    }
                }
            }
        }

        /*
         * Queremos controlar mais território
         * do que o adversário.
         */
        score +=
            (minhasCasas - casasInimigo) * 12;

        score -=
            disputadas * 2;

        return score;
    }

    // =========================================================
    // CACA
    // =========================================================

    private static int avaliarCaca(
        GameState state,
        Snake me,
        Snake enemy,
        Coordinate myNext
    ) {

        int score = 0;

        // -----------------------------------------------------
        // MORTE FORCADA
        // -----------------------------------------------------

        if (
            podeForcarMorte(
                state,
                me,
                enemy,
                myNext,
                PROFUNDIDADE_CACA
            )
        ) {
            score += 10000;
        }

        List<Coordinate> enemyMoves =
            getPossibleEnemyMoves(
                state,
                enemy,
                myNext,
                me
            );

        if (enemyMoves.isEmpty()) {
            return score + 6000;
        }

        // -----------------------------------------------------
        // MENOS OPCOES PARA O ADVERSARIO
        // -----------------------------------------------------

        if (enemyMoves.size() == 1) {

            score += 2200;

        } else if (enemyMoves.size() == 2) {

            score += 900;

        } else if (enemyMoves.size() == 3) {

            score += 250;
        }

        // -----------------------------------------------------
        // MELHOR FUGA DO ADVERSARIO
        // -----------------------------------------------------

        int melhorEspacoInimigo = 0;

        for (Coordinate enemyNext : enemyMoves) {

            int enemySpace =
                floodFill(
                    state,
                    enemyNext,
                    myNext
                );

            melhorEspacoInimigo =
                Math.max(
                    melhorEspacoInimigo,
                    enemySpace
                );

            /*
             * H2H favorável.
             */
            if (
                headToHeadFavoravel(
                    me,
                    enemy,
                    myNext,
                    enemyNext
                )
            ) {
                score += 3000;
            }
        }

        int total =
            state.getBoard().getWidth() *
            state.getBoard().getHeight();

        score +=
            (total - melhorEspacoInimigo) * 12;

        // -----------------------------------------------------
        // PRESSIONAR CONTRA PAREDE
        // -----------------------------------------------------

        int wallDistance =
            distanciaParede(
                state,
                enemy.getHead()
            );

        if (wallDistance == 0) {
            score += 450;

        } else if (wallDistance == 1) {
            score += 220;
        }

        // -----------------------------------------------------
        // VANTAGEM DE TAMANHO
        // -----------------------------------------------------

        score +=
            (
                me.getLength() -
                enemy.getLength()
            ) * 70;

        return score;
    }

    // =========================================================
    // MORTE FORCADA
    // =========================================================

    private static boolean podeForcarMorte(
        GameState state,
        Snake me,
        Snake enemy,
        Coordinate myNext,
        int profundidade
    ) {

        List<Coordinate> respostas =
            getPossibleEnemyMoves(
                state,
                enemy,
                myNext,
                me
            );

        if (respostas.isEmpty()) {
            return true;
        }

        /*
         * Para ser morte forçada,
         * TODAS as respostas do inimigo precisam ser ruins.
         */
        for (Coordinate enemyNext : respostas) {

            if (
                inimigoTemLinhaDeSobrevivencia(
                    state,
                    me,
                    enemy,
                    myNext,
                    enemyNext,
                    profundidade - 1
                )
            ) {

                return false;
            }
        }

        return true;
    }

    private static boolean inimigoTemLinhaDeSobrevivencia(
        GameState state,
        Snake me,
        Snake enemy,
        Coordinate myPosition,
        Coordinate enemyPosition,
        int profundidade
    ) {

        if (profundidade <= 0) {

            int space =
                floodFill(
                    state,
                    enemyPosition,
                    myPosition
                );

            return space > enemy.getLength();
        }

        List<Coordinate> movimentos =
            getPossibleEnemyMovesFromPosition(
                state,
                enemy,
                enemyPosition,
                myPosition,
                me
            );

        if (movimentos.isEmpty()) {
            return false;
        }

        /*
         * O inimigo escolherá a melhor resposta para ele.
         * Basta existir UMA linha segura para não ser
         * morte forçada.
         */
        for (Coordinate next : movimentos) {

            if (
                inimigoTemLinhaDeSobrevivencia(
                    state,
                    me,
                    enemy,
                    myPosition,
                    next,
                    profundidade - 1
                )
            ) {

                return true;
            }
        }

        return false;
    }

    // =========================================================
    // MOVIMENTOS FUTUROS DO ADVERSARIO
    // =========================================================

    private static List<Coordinate> getPossibleEnemyMovesFromPosition(
        GameState state,
        Snake enemy,
        Coordinate currentPosition,
        Coordinate myPosition,
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
                    currentPosition,
                    direction
                );

            if (!insideBoard(state, next)) {
                continue;
            }

            /*
             * Nossa cabeça controla essa casa caso
             * sejamos maiores.
             */
            if (
                same(next, myPosition) &&
                me.getLength() >
                enemy.getLength()
            ) {
                continue;
            }

            /*
             * Corpos atuais continuam sendo obstáculos
             * nessa simulação simplificada.
             */
            if (occupied(state, next)) {
                continue;
            }

            moves.add(next);
        }

        return moves;
    }

    // =========================================================
    // HEAD TO HEAD
    // =========================================================

    private static boolean headToHeadFavoravel(
        Snake me,
        Snake enemy,
        Coordinate myNext,
        Coordinate enemyNext
    ) {

        return
            same(myNext, enemyNext) &&
            me.getLength() >
            enemy.getLength();
    }

    private static boolean headToHeadPerigoso(
        Snake me,
        Snake enemy,
        Coordinate myNext,
        Coordinate enemyNext
    ) {

        return
            same(myNext, enemyNext) &&
            me.getLength() <=
            enemy.getLength();
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

        // -----------------------------------------------------
        // SAIDAS
        // -----------------------------------------------------

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

        // -----------------------------------------------------
        // COMPACTACAO
        // -----------------------------------------------------

        score -=
            penalidadeCompactacao(
                corpoSimulado
            );

        // -----------------------------------------------------
        // LOOKAHEAD
        // -----------------------------------------------------

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

            if (
                colisaoComCorpoSimulado(
                    corpo,
                    next
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
    // COMPACTACAO
    // =========================================================

    private static int penalidadeCompactacao(
        List<Coordinate> corpo
    ) {

        int penalidade = 0;

        for (
            int i = 0;
            i < corpo.size();
            i++
        ) {

            Coordinate atual =
                corpo.get(i);

            for (
                int j = i + 2;
                j < corpo.size();
                j++
            ) {

                Coordinate outra =
                    corpo.get(j);

                int distancia =
                    distanciaManhattan(
                        atual,
                        outra
                    );

                if (distancia == 1) {

                    penalidade += 50;

                } else if (distancia == 2) {

                    penalidade += 12;
                }
            }
        }

        return penalidade;
    }

    // =========================================================
    // SAIDAS
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

            if (
                colisaoComCorpoSimulado(
                    corpo,
                    next
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

    private static int contarSaidasImediatas(
        GameState state,
        Snake me,
        Coordinate position
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
                    position,
                    direction
                );

            if (!insideBoard(state, next)) {
                continue;
            }

            if (occupied(state, next)) {
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
        Coordinate position
    ) {

        if (
            corpo == null ||
            corpo.isEmpty()
        ) {
            return false;
        }

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
    // COMIDA
    // =========================================================

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
            return 1700;
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

            weight = 140;

        } else if (me.getHealth() <= 35) {

            weight = 95;

        } else {

            weight = 45;
        }

        return
            Math.max(
                0,
                20 - distance
            ) * weight;
    }

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

                if (
                    enemy.getLength() >=
                    me.getLength() &&
                    enemyDistance <=
                    myDistance
                ) {
                    continue;
                }
            }

            if (myDistance < bestDistance) {

                bestDistance =
                    myDistance;

                best =
                    food;
            }
        }

        return best;
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

            if (!insideBoard(state, next)) {
                return true;
            }

            if (occupied(state, next)) {
                return true;
            }

            if (enemy == null) {
                return false;
            }

            List<Coordinate> enemyMoves =
                getPossibleEnemyMoves(
                    state,
                    enemy,
                    null,
                    me
                );

            for (Coordinate enemyNext : enemyMoves) {

                /*
                 * Casas onde perderiamos ou empatariamos
                 * head-to-head sao tratadas como perigosas.
                 */
                if (
                    headToHeadPerigoso(
                        me,
                        enemy,
                        next,
                        enemyNext
                    )
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

            if (
                extraBlocked != null &&
                same(
                    next,
                    extraBlocked
                )
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
    // CENTRO / PAREDE
    // =========================================================

    private static int centerScore(
        GameState state,
        Coordinate position
    ) {

        int centerX =
            state.getBoard().getWidth() / 2;

        int centerY =
            state.getBoard().getHeight() / 2;

        int distance =
            distanciaManhattan(
                position,
                coordinate(
                    centerX,
                    centerY
                )
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
            return -90;
        }

        if (distance == 1) {
            return -30;
        }

        if (distance == 2) {
            return 10;
        }

        return 25;
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
                Math.min(
                    left,
                    right
                ),
                Math.min(
                    down,
                    up
                )
            );
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

    private static int distanciaManhattan(
        Coordinate first,
        Coordinate second
    ) {

        return
            Math.abs(
                first.getX() -
                second.getX()
            )
            +
            Math.abs(
                first.getY() -
                second.getY()
            );
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