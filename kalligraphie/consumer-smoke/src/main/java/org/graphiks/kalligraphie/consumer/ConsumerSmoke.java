package org.graphiks.kalligraphie.consumer;

import org.graphiks.kalligraphie.Kalligraphie;
import org.graphiks.kalligraphie.api.FontOperationResult;
import org.graphiks.kalligraphie.api.FontSourceProvenance;

public final class ConsumerSmoke {
    private ConsumerSmoke() {
    }

    public static void main(String[] args) {
        var result = Kalligraphie.INSTANCE.embedded(
            new byte[0],
            new FontSourceProvenance("published-consumer-smoke")
        );
        if (!(result instanceof FontOperationResult.Failure)) {
            throw new IllegalStateException("Malformed bytes must produce a typed failure.");
        }
        System.out.println("Consumed org.graphiks:kalligraphie through its published runtime graph.");
    }
}
