import com.fazecast.jSerialComm.SerialPort;
import java.io.OutputStream;
import java.io.IOException;
import java.sql.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.Random;

public class VFDDisplay {
    private SerialPort serialPort;
    private Connection connection;
    private String lastValue = "0000"; // Armazena o último valor enviado ao VFD
    private Random random = new Random(); // Instância para gerar números aleatórios

    private long zeroValueInterval = 5; // Intervalo para mensagens quando o valor da comanda é zero (em segundos)
    private long nonZeroValueInterval = 1; // Intervalo para mensagens quando o valor da comanda não é zero (em segundos)

    // Lista de mensagens para quando a venda for zero
    private String[] zeroValueMessages = {
            "Voltem sempre!!        ",
            "Seja Bem Vindo!!      ",
            "Aguardando novas    vendas.",
            "Nada para exibir no momento.",
            "Comanda vazia.         "
    };

    private int carouselIndex = 0; // Índice atual do carrossel
    private boolean isCarouselMode = false; // Estado do carrossel

    // Método para conectar à porta serial usando jSerialComm
    public void connectSerial(String portName) {
        serialPort = SerialPort.getCommPort(portName); // Obtém a porta
        serialPort.setComPortParameters(9600, 8, SerialPort.ONE_STOP_BIT, SerialPort.NO_PARITY); // Configura a porta
        serialPort.setComPortTimeouts(SerialPort.TIMEOUT_WRITE_BLOCKING, 0, 0); // Timeout para escrita

        if (serialPort.openPort()) {
            System.out.println("Porta serial conectada com sucesso.");
        } else {
            System.err.println("Erro ao conectar à porta serial.");
        }
    }

    // Método para conectar ao banco de dados SQL Server
    public void connectDatabase() {
        String connectionUrl = "jdbc:sqlserver://localhost:1433;databaseName=MARQUINHO_LANCHES;user=ca;password=qaplcba2299;encrypt=true;trustServerCertificate=true;";

        try {
            Class.forName("com.microsoft.sqlserver.jdbc.SQLServerDriver");
            connection = DriverManager.getConnection(connectionUrl);
            System.out.println("Conectado ao banco de dados com sucesso!");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // Método para obter o valor da venda do banco de dados
    public String getValorVenda() {
        String comandasValor = "0000"; // Valor padrão
        String query = "SELECT SUM(valor) AS soma FROM \n" +
                "(\n" +
                "    SELECT ISNULL(SUM(valor), 0) AS valor \n" +
                "    FROM Comandas_Itens \n" +
                "    WHERE Comanda IN (SELECT Comanda FROM Comanda WHERE Posicao IS NOT NULL) AND STATUS IS NULL \n" +
                "    UNION \n" +
                "    SELECT ISNULL(SUM(valor), 0) AS valor \n" +
                "    FROM Comandas_Detalhes \n" +
                "    WHERE Comanda IN (SELECT Comanda FROM Comanda WHERE Posicao IS NOT NULL)\n" +
                ") AS TESTE;";

        if (connection != null) {
            try (Statement statement = connection.createStatement();
                 ResultSet resultSet = statement.executeQuery(query)) {

                if (resultSet.next()) {
                    comandasValor = resultSet.getString("soma");
                }

            } catch (SQLException e) {
                System.err.println("Erro ao executar consulta SQL: " + e.getMessage());
            }
        } else {
            System.err.println("A conexão com o banco de dados não foi estabelecida.");
        }

        return comandasValor;
    }

    // Método para enviar mensagem ao VFD com formatação
    public void sendToVFD(String message) {
        if (serialPort != null && serialPort.isOpen()) {
            try {
                OutputStream out = serialPort.getOutputStream();

                // Limpar a tela antes de enviar uma nova mensagem
                clearVFD(out);

                // Formatar e enviar a mensagem para o display
                String formattedMessage = formatMessageForVFD(message);
                out.write(formattedMessage.getBytes("UTF-8")); // Especificando UTF-8 para evitar problemas de codificação
                out.flush();
            } catch (IOException e) {
                System.err.println("Erro ao enviar mensagem para o VFD: " + e.getMessage());
            }
        } else {
            System.err.println("Erro: A conexão com o VFD não foi estabelecida.");
        }
    }

    // Método para formatar a mensagem de acordo com as limitações do VFD
    private String formatMessageForVFD(String message) {
        int maxLength = 27; // Exemplo de limite de caracteres do VFD (ajustar conforme necessário)
        String formattedMessage;

        // Se a mensagem for maior que o tamanho permitido, faz a rolagem de texto
        if (message.length() > maxLength) {
            // Rolar o texto (apenas um exemplo simples, pode ser ajustado conforme necessário)
            formattedMessage = message.substring(0, maxLength);
        } else {
            // Centralizar a mensagem se for menor que o limite
            int padding = (maxLength - message.length()) / 2;
            StringBuilder sb = new StringBuilder();

            for (int i = 0; i < padding; i++) {
                sb.append(" ");
            }
            sb.append(message);
            formattedMessage = sb.toString();
        }

        return formattedMessage;
    }

    // Método para limpar a tela do VFD
    private void clearVFD(OutputStream out) throws IOException {
        byte[] clearCommand = new byte[]{0x0C};  // Comando para limpar a tela
        out.write(clearCommand);
        out.flush();
    }

    // Método para verificar e atualizar o VFD se necessário
    private void checkAndUpdateVFD() {
        String currentValue = getValorVenda(); // Obtém o valor atual da venda

        if (currentValue == null || currentValue.equals("0") || currentValue.equals("0.00")) {
            // Modo Carrossel
            isCarouselMode = true;
            String currentMessage = zeroValueMessages[carouselIndex];
            sendToVFD(currentMessage);

            // Atualizar o índice do carrossel para a próxima mensagem
            carouselIndex = (carouselIndex + 1) % zeroValueMessages.length;
            lastValue = currentMessage; // Atualiza o último valor para evitar repetir a mesma mensagem consecutivamente
        } else {
            // Modo Exibição de Valor
            if (!currentValue.equals(lastValue)) {
                isCarouselMode = false;
                String formattedValue = "Valor R$" + currentValue;
                sendToVFD(formattedValue);
                lastValue = formattedValue; // Atualiza o último valor enviado
            }
        }
    }

    // Método para iniciar a verificação periódica com intervalos variáveis
    public void startMonitoring() {
        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);

        // Definir a tarefa de verificação
        Runnable checkTask = new Runnable() {
            @Override
            public void run() {
                try {
                    // Verificar e atualizar o VFD
                    checkAndUpdateVFD();

                    // Escolher o intervalo de tempo com base no modo atual
                    long interval = isCarouselMode ? zeroValueInterval : nonZeroValueInterval;

                    // Agendar a próxima verificação com o intervalo apropriado
                    scheduler.schedule(this, interval, TimeUnit.SECONDS);
                } catch (Exception e) {
                    System.err.println("Erro na tarefa de monitoramento: " + e.getMessage());
                    // Opcional: Reagendar a tarefa mesmo em caso de erro
                    scheduler.schedule(this, nonZeroValueInterval, TimeUnit.SECONDS);
                }
            }
        };

        // Iniciar a primeira verificação imediatamente
        scheduler.schedule(checkTask, 0, TimeUnit.SECONDS);
    }

    // Método para fechar a conexão com o VFD
    public void closeSerial() {
        if (serialPort != null && serialPort.isOpen()) {
            serialPort.closePort();
            System.out.println("Conexão com a porta serial fechada.");
        }
    }

    // Método para fechar a conexão com o banco de dados
    public void closeDatabase() {
        if (connection != null) {
            try {
                if (!connection.isClosed()) {
                    connection.close();
                }
            } catch (SQLException e) {
                System.err.println("Erro ao fechar a conexão com o banco de dados: " + e.getMessage());
            }
        }
    }

    // Método para listar a primeira porta disponível (automático)
    public String escolherPrimeiraPortaDisponivel() {
        SerialPort[] portList = SerialPort.getCommPorts();

        if (portList.length > 0) {
            System.out.println("Porta disponível encontrada: " + portList[0].getSystemPortName());
            return portList[0].getSystemPortName(); // Retorna a primeira porta disponível
        } else {
            System.err.println("Nenhuma porta serial disponível. Usando COM1 como padrão.");
            return "COM1"; // Retorna COM1 se nenhuma porta for encontrada
        }
    }

    public static void main(String[] args) {
        VFDDisplay vfd = new VFDDisplay();

        try {
            // Escolhe automaticamente a primeira porta COM disponível
            String portaEscolhida = vfd.escolherPrimeiraPortaDisponivel();
            vfd.connectSerial(portaEscolhida);

            // Conectar ao banco de dados SQL Server
            vfd.connectDatabase();

            /// Iniciar monitoramento periódico para verificar atualizações
            vfd.startMonitoring();

        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            // Fechar a conexão com o banco de dados e a porta serial (será fechado quando o programa terminar)
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                vfd.closeDatabase();
                vfd.closeSerial();
            }));
        }
    }
}
