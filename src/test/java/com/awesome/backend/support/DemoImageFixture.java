package com.awesome.backend.support;

import com.awesome.backend.demo.entity.DemoProduct;
import com.awesome.backend.demo.repository.DemoProductRepository;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;

/**
 * 촬영 이미지가 있는 상품을 테스트에서 만든다.
 *
 * <p>실제 데이터셋 사진은 S3 에 있고 저장소에 커밋하지 않는다 (D-25). 테스트는 대신 여기서
 * 사진을 만들어 로컬 보관소에 넣는다 — 파일이 있기만 하면 되고, mock 추론은 사진을 보지 않는다.
 *
 * <p>보관 위치는 {@link #BASE_PATH} 이며 테스트가 {@code storage.local-base-path} 로 같은 값을 준다.
 */
public final class DemoImageFixture {

    /** build 아래라 저장소에 남지 않는다. */
    public static final String BASE_PATH = "build/test-images";

    private static final short CAMERA_COUNT = 3;

    private DemoImageFixture() {
    }

    /** 데모 상품 행과 사진 3장을 만든다. 이미 있으면 덮어쓴다. */
    public static void create(DemoProductRepository demoProducts, String gtin) {
        Path dir = Path.of(BASE_PATH, "images", gtin);
        try {
            Files.createDirectories(dir);
            for (short cameraNo = 1; cameraNo <= CAMERA_COUNT; cameraNo++) {
                Files.write(dir.resolve("cam" + cameraNo + ".jpg"), jpeg(cameraNo));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        demoProducts.save(new DemoProduct(gtin, DemoProduct.Pool.INBOUND,
                new BigDecimal("10.0"), new BigDecimal("8.0"), new BigDecimal("5.0"),
                "images/" + gtin));
    }

    /** 모델 입력 크기(288x512)에 맞춘 단색 이미지. 카메라마다 색을 달리해 파일이 구분된다. */
    private static byte[] jpeg(short cameraNo) throws IOException {
        BufferedImage image = new BufferedImage(512, 288, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(new Color(40 * cameraNo, 80, 120));
        g.fillRect(0, 0, 512, 288);
        g.dispose();
        var out = new java.io.ByteArrayOutputStream();
        ImageIO.write(image, "jpg", out);
        return out.toByteArray();
    }
}
