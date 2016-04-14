package com.airbnb.aerosolve.core.models;

import com.airbnb.aerosolve.core.DebugScoreRecord;
import com.airbnb.aerosolve.core.FeatureVector;
import com.airbnb.aerosolve.core.ModelHeader;
import com.airbnb.aerosolve.core.ModelRecord;
import com.airbnb.aerosolve.core.features.FeatureRegistry;
import com.airbnb.aerosolve.core.util.FloatVector;
import com.airbnb.aerosolve.core.util.StringDictionary;
import com.airbnb.aerosolve.core.util.SupportVector;
import com.airbnb.aerosolve.core.util.Util;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

// A kernel machine with arbitrary kernels. Different support vectors can have different kernels.
// The conversion from sparse features to dense features is done by dictionary lookup. Also since
// non-linear kernels are used there is no need to cross features, the feature interactions are done by
// considering kernel responses to the support vectors. Try to keep features under a thousand.
@Accessors(fluent = true, chain = true)
public class KernelModel extends AbstractModel {
  private static final long serialVersionUID = 7651061358422885397L;
  
  @Getter @Setter
  StringDictionary dictionary;
  
  @Getter @Setter
  List<SupportVector> supportVectors;

  public KernelModel(FeatureRegistry registry) {
    super(registry);
    dictionary = new StringDictionary();
    supportVectors = new ArrayList<>();
  }

  @Override
  public double scoreItem(FeatureVector combinedItem) {
    FloatVector vec = dictionary.makeVectorFromSparseFloats(combinedItem);
    float sum = 0.0f;
    for (SupportVector sv : supportVectors) {
      sum += sv.evaluate(vec);
    }
    return sum;
  }
  
  @Override
  public double debugScoreItem(FeatureVector combinedItem,
                              StringBuilder builder) {
    return 0.0f;
  }
  
  @Override
  public List<DebugScoreRecord> debugScoreComponents(FeatureVector combinedItem) {
    // (TODO) implement debugScoreComponents
    return new ArrayList<>();
  }

  @Override
  public void onlineUpdate(double grad, double learningRate, FeatureVector vector) {
    FloatVector vec = dictionary.makeVectorFromSparseFloats(vector);
    double deltaG = - learningRate * grad;
    for (SupportVector sv : supportVectors) {
      double response = sv.evaluateUnweighted(vec);
      double deltaW = deltaG * response;
      sv.setWeight((float) (sv.getWeight() + deltaW));
    }
  }

  @Override
  public void save(BufferedWriter writer) throws IOException {
    ModelHeader header = new ModelHeader();
    header.setModelType("kernel");
    header.setDictionary(dictionary.getDictionary());
    long count = supportVectors.size();
    header.setNumRecords(count);
    ModelRecord headerRec = new ModelRecord();
    headerRec.setModelHeader(header);
    writer.write(Util.encode(headerRec));
    writer.newLine();
    for (SupportVector sv : supportVectors) {
      writer.write(Util.encode(sv.toModelRecord()));
      writer.newLine();
    }
    writer.flush();
  }

  @Override
  protected void loadInternal(ModelHeader header, BufferedReader reader) throws IOException {
    long rows = header.getNumRecords();
    dictionary = new StringDictionary(header.getDictionary());

    supportVectors = new ArrayList<>();
    for (long i = 0; i < rows; i++) {
      String line = reader.readLine();
      ModelRecord record = Util.decodeModel(line);
      supportVectors.add(new SupportVector(record));
    }
  }

}
