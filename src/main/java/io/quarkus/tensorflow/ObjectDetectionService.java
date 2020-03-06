package io.quarkus.tensorflow;

import com.google.protobuf.TextFormat;
import object_detection.protos.StringIntLabelMapOuterClass;
import org.apache.commons.imaging.ImageInfo;
import org.apache.commons.imaging.ImageReadException;
import org.apache.commons.imaging.Imaging;
import org.tensorflow.SavedModelBundle;
import org.tensorflow.Tensor;
import org.tensorflow.types.UInt8;

import javax.annotation.PostConstruct;
import javax.enterprise.context.ApplicationScoped;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

@ApplicationScoped
public class ObjectDetectionService {

    private static final String LABEL_RESOURCE_PATH = "labels/mscoco_label_map.pbtxt";
    private static final String MODEL_FILE_PATH = "/models/ssd_inception_v2_coco_2017_11_17/saved_model";

    private SavedModelBundle model;
    private String[] labels;

    @PostConstruct
    void loadModel() throws TextFormat.ParseException {
        this.model = SavedModelBundle.load(System.getProperty("user.dir") + MODEL_FILE_PATH, "serve");
        this.labels = loadLabels();
    }

    public String[] getLabels() {
        return labels;
    }

    public List<ObjectDetectionResult> detect(byte[] rawData) throws ImageReadException, IOException, URISyntaxException {
        List<Tensor<?>> outputs = null;

        Tensor<UInt8> input = makeImageTensor(rawData);
        outputs = this.model.session().runner().feed("image_tensor", input).fetch("detection_scores")
                .fetch("detection_classes").fetch("detection_boxes").run();
        Tensor<Float> scoresT = outputs.get(0).expect(Float.class);
        Tensor<Float> classesT = outputs.get(1).expect(Float.class);
        Tensor<Float> boxesT = outputs.get(2).expect(Float.class);

        // All these tensors have: 1 as the first dimension, maxObjects as the second dimension
        // Boxes will have 4 as the third dimension (2 sets of (x, y) coordinates).
        int maxObjects = (int) scoresT.shape()[1];
        float[] scores = scoresT.copyTo(new float[1][maxObjects])[0];
        float[] classes = classesT.copyTo(new float[1][maxObjects])[0];
        float[][] boxes = boxesT.copyTo(new float[1][maxObjects][4])[0];

        List<ObjectDetectionResult> results = new ArrayList<>();

        for(int object = 0; object < maxObjects; object++) {

            if (scores[object] < 0.5) {
                continue;
            }

            String label = labels[(int) classes[object]];
            float score = scores[object];

            float x1 = boxes[object][0];
            float y1 = boxes[object][1];
            float x2 = boxes[object][2];
            float y2 = boxes[object][3];

            ObjectDetectionResult result = new ObjectDetectionResult(label, score, x1, y1, x2, y2);
            results.add(result);
        }

        return results;
    }

    private static String[] loadLabels() throws TextFormat.ParseException {
        String text = ObjectDetectionUtil.convertStreamToString(ClassLoader.getSystemResourceAsStream(LABEL_RESOURCE_PATH));
        StringIntLabelMapOuterClass.StringIntLabelMap.Builder builder = StringIntLabelMapOuterClass.StringIntLabelMap.newBuilder();
        TextFormat.merge(text, builder);
        StringIntLabelMapOuterClass.StringIntLabelMap proto = builder.build();
        int maxId = 0;
        for (StringIntLabelMapOuterClass.StringIntLabelMapItem item : proto.getItemList()) {
            if (item.getId() > maxId) {
                maxId = item.getId();
            }
        }
        String[] ret = new String[maxId + 1];
        for (StringIntLabelMapOuterClass.StringIntLabelMapItem item : proto.getItemList()) {
            ret[item.getId()] = item.getDisplayName();
        }
        return ret;
    }

    private static Tensor<UInt8> makeImageTensor(byte[] rawData) throws IOException, URISyntaxException, ImageReadException {
        // Get metadata about the image from the raw bytes
        ImageInfo imageInfo = Imaging.getImageInfo(rawData);
        // Load the image from the raw bytes
        BufferedImage img = Imaging.getBufferedImage(rawData);
        // Get the image as an array of RGB integer values
        int[] rgbInts = ((DataBufferInt) img.getData().getDataBuffer()).getData();
        // Convert RGB values into byte[3] representation
        byte[] rgbBytes = ObjectDetectionUtil.convertRGBstoBytes(rgbInts);
        // Convert the RGB bytes to a ByteBuffer
        ByteBuffer byteBuffer = ByteBuffer.wrap(rgbBytes);

        final long BATCH_SIZE = 1;
        final long CHANNELS = 3;
        long[] shape = new long[]{BATCH_SIZE, imageInfo.getHeight(), imageInfo.getWidth(), CHANNELS};
        return Tensor.create(UInt8.class, shape, byteBuffer);
    }
}