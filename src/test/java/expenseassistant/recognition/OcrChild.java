package expenseassistant.recognition;

public final class OcrChild {
    private OcrChild() {}

    public static void main(String[] args) throws Exception {
        String testPayload = "MOCK_TAX_888800001111 MOCK_TEL_18800000000 OCR_PRIVATE_SENTINEL";
        System.err.println(testPayload);
        System.out.println("level\tpage_num\tblock_num\tpar_num\tline_num\tword_num\tleft\ttop\twidth\theight\tconf\ttext");
        System.out.println("5\t1\t1\t1\t1\t1\t0\t0\t20\t10\t95\t" + testPayload);
        System.out.flush();
        if ("block".equals(args[0])) {
            Thread.sleep(30_000);
        } else if ("fail".equals(args[0])) {
            System.exit(2);
        }
    }
}
