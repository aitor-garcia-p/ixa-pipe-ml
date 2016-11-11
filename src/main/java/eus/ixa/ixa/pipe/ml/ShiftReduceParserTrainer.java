/*
 *Copyright 2016 Rodrigo Agerri

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
 */

package eus.ixa.ixa.pipe.ml;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.Map;

import eus.ixa.ixa.pipe.ml.features.XMLFeatureDescriptor;
import eus.ixa.ixa.pipe.ml.parse.AncoraHeadRules.AncoraHeadRulesSerializer;
import eus.ixa.ixa.pipe.ml.parse.HeadRules;
import eus.ixa.ixa.pipe.ml.parse.Parse;
import eus.ixa.ixa.pipe.ml.parse.ParseSampleStream;
import eus.ixa.ixa.pipe.ml.parse.ParserEvaluator;
import eus.ixa.ixa.pipe.ml.parse.ParserFactory;
import eus.ixa.ixa.pipe.ml.parse.ParserModel;
import eus.ixa.ixa.pipe.ml.parse.PennTreebankHeadRules.PennTreebankHeadRulesSerializer;
import eus.ixa.ixa.pipe.ml.parse.ShiftReduceParser;
import eus.ixa.ixa.pipe.ml.resources.LoadModelResources;
import eus.ixa.ixa.pipe.ml.sequence.BilouCodec;
import eus.ixa.ixa.pipe.ml.sequence.BioCodec;
import eus.ixa.ixa.pipe.ml.sequence.SequenceLabelerCodec;
import eus.ixa.ixa.pipe.ml.sequence.SequenceLabelerFactory;
import eus.ixa.ixa.pipe.ml.sequence.SequenceLabelerModel;
import eus.ixa.ixa.pipe.ml.utils.Flags;
import eus.ixa.ixa.pipe.ml.utils.IOUtils;
import opennlp.tools.cmdline.TerminateToolException;
import opennlp.tools.dictionary.Dictionary;
import opennlp.tools.util.ObjectStream;
import opennlp.tools.util.TrainingParameters;
import opennlp.tools.util.model.ArtifactSerializer;

public class ShiftReduceParserTrainer {

  /**
   * The language.
   */
  private final String lang;
  /**
   * String holding the training data.
   */
  private final String trainData;
  /**
   * String holding the testData.
   */
  private final String testData;
  /**
   * ObjectStream of the training data.
   */
  private final ObjectStream<Parse> trainSamples;
  /**
   * ObjectStream of the test data.
   */
  private final ObjectStream<Parse> testSamples;
  /**
   * The head rules.
   */
  private final HeadRules rules;
  /**
   * The parser factory.
   */
  private ParserFactory parserFactory;
  /**
   * The sequence encoding of the named entity spans, e.g., BIO or BILOU.
   */
  private String sequenceCodec;
  /**
   * The sequence labeler factory for the tagger.
   */
  private SequenceLabelerFactory taggerFactory;
  /**
   * The sequence labeler factory for the chunker.
   */
  private SequenceLabelerFactory chunkerFactory;

  /**
   * Construct a trainer with training and test data, and with options for
   * language, beamsize for decoding, sequence codec and corpus format (conll or
   * opennlp).
   *
   * @param params
   *          the training parameters
   * @param taggerParams
   *          the tagger parameters
   * @param chunkerParams
   *          the chunker parameters
   * @throws IOException
   *           io exception
   */
  public ShiftReduceParserTrainer(final TrainingParameters params,
      final TrainingParameters taggerParams,
      final TrainingParameters chunkerParams) throws IOException {

    this.lang = Flags.getLanguage(params);
    this.trainData = params.getSettings().get("TrainSet");
    this.testData = params.getSettings().get("TestSet");
    this.trainSamples = getParseStream(this.trainData);
    this.testSamples = getParseStream(this.testData);
    this.rules = getHeadRules(params);
    createParserFactory(params);
    setTaggerFactory(createSequenceLabelerFactory(taggerParams));
    setChunkerFactory(createSequenceLabelerFactory(chunkerParams));
  }

  /**
   * Construct a trainer with training and test data, and with options for
   * language, beamsize for decoding, sequence codec and corpus format (conll or
   * opennlp).
   *
   * @param params
   *          the training parameters
   * @param chunkerParams
   *          the chunker parameters
   * @throws IOException
   *           io exception
   */
  public ShiftReduceParserTrainer(final TrainingParameters params,
      final TrainingParameters chunkerParams) throws IOException {

    this.lang = Flags.getLanguage(params);
    this.trainData = params.getSettings().get("TrainSet");
    this.testData = params.getSettings().get("TestSet");
    this.trainSamples = getParseStream(this.trainData);
    this.testSamples = getParseStream(this.testData);
    this.rules = getHeadRules(params);
    createParserFactory(params);
    setChunkerFactory(createSequenceLabelerFactory(chunkerParams));
  }

  public void createParserFactory(final TrainingParameters params)
      throws IOException {
    final Dictionary autoDict = ShiftReduceParser
        .buildDictionary(this.trainSamples, this.rules, params);
    final Map<String, Object> resources = LoadModelResources
        .loadParseResources(params);
    setParserFactory(ParserFactory.create(ParserFactory.class.getName(),
        autoDict, resources));
  }

  public SequenceLabelerFactory createSequenceLabelerFactory(
      final TrainingParameters params) throws IOException {
    final String seqCodec = getSequenceCodec();
    final SequenceLabelerCodec<String> sequenceCodec = SequenceLabelerFactory
        .instantiateSequenceCodec(seqCodec);
    final String featureDescription = XMLFeatureDescriptor
        .createXMLFeatureDescriptor(params);
    System.err.println(featureDescription);
    final byte[] featureGeneratorBytes = featureDescription
        .getBytes(Charset.forName("UTF-8"));
    final Map<String, Object> resources = LoadModelResources
        .loadSequenceResources(params);
    return SequenceLabelerFactory.create(SequenceLabelerFactory.class.getName(),
        featureGeneratorBytes, resources, sequenceCodec);
  }

  /**
   * Train a parser model from the Treebank data.
   * 
   * @param params
   *          the parser parameters
   * @param taggerParams
   *          the pos tagger parameters
   * @param chunkerParams
   *          the chunker parameters
   * @return a parser model
   */
  public final ParserModel train(final TrainingParameters params,
      final TrainingParameters taggerParams,
      final TrainingParameters chunkerParams) {
    if (getParserFactory() == null) {
      throw new IllegalStateException(
          "The ParserFactory must be instantiated!!");
    }
    if (getTaggerFactory() == null) {
      throw new IllegalStateException(
          "The TaggerFactory must be instantiated!");
    }
    ParserModel trainedModel = null;
    ParserEvaluator parserEvaluator = null;
    try {
      trainedModel = ShiftReduceParser.train(this.lang, this.trainSamples,
          this.rules, params, this.parserFactory, taggerParams,
          this.taggerFactory, chunkerParams, this.chunkerFactory);
      final ShiftReduceParser parser = new ShiftReduceParser(trainedModel);
      parserEvaluator = new ParserEvaluator(parser);
      parserEvaluator.evaluate(this.testSamples);
    } catch (final IOException e) {
      System.err.println("IO error while loading training and test sets!");
      e.printStackTrace();
      System.exit(1);
    }
    System.out.println("Final Result: \n" + parserEvaluator.getFMeasure());
    return trainedModel;
  }

  /**
   * Train a parser model providing an already trained POS tagger.
   * 
   * @param params
   *          the parser parameters
   * @param taggerModel
   *          the POS tagger model
   * @param chunkerParams
   *          the chunker parameters
   * @return the parser model
   */
  public final ParserModel train(final TrainingParameters params,
      final InputStream taggerModel, final TrainingParameters chunkerParams) {
    if (getParserFactory() == null) {
      throw new IllegalStateException(
          "The ParserFactory must be instantiated!!");
    }
    SequenceLabelerModel posModel = null;
    try {
      posModel = new SequenceLabelerModel(taggerModel);
    } catch (final IOException e1) {
      e1.printStackTrace();
    }
    ParserModel trainedModel = null;
    ParserEvaluator parserEvaluator = null;
    try {
      trainedModel = ShiftReduceParser.train(this.lang, this.trainSamples,
          this.rules, params, this.parserFactory, posModel, chunkerParams,
          this.chunkerFactory);
      final ShiftReduceParser parser = new ShiftReduceParser(trainedModel);
      parserEvaluator = new ParserEvaluator(parser);
      parserEvaluator.evaluate(this.testSamples);
    } catch (final IOException e) {
      System.err.println("IO error while loading training and test sets!");
      e.printStackTrace();
      System.exit(1);
    }
    System.out.println("Final Result: \n" + parserEvaluator.getFMeasure());
    return trainedModel;
  }

  /**
   * Getting the stream with the right corpus format.
   *
   * @param inputData
   *          the input data
   * @return the stream from the several corpus formats
   * @throws IOException
   *           the io exception
   */
  public static ObjectStream<Parse> getParseStream(final String inputData)
      throws IOException {
    final ObjectStream<String> parseStream = IOUtils
        .readFileIntoMarkableStreamFactory(inputData);
    final ObjectStream<Parse> samples = new ParseSampleStream(parseStream);
    return samples;
  }

  @SuppressWarnings("rawtypes")
  public static HeadRules getHeadRules(final TrainingParameters params)
      throws IOException {

    ArtifactSerializer headRulesSerializer = null;
    if (Flags.getLanguage(params).equalsIgnoreCase("en")) {
      headRulesSerializer = new PennTreebankHeadRulesSerializer();
    } else if (Flags.getLanguage(params).equalsIgnoreCase("es")) {
      headRulesSerializer = new AncoraHeadRulesSerializer();
    } else {
      System.err.println("HeadRules not suported for language "
          + Flags.getLanguage(params) + "!!");
    }
    final Object headRulesObject = headRulesSerializer
        .create(new FileInputStream(Flags.getHeadRulesFile(params)));
    if (headRulesObject instanceof HeadRules) {
      return (HeadRules) headRulesObject;
    } else {
      throw new TerminateToolException(-1,
          "HeadRules Artifact Serializer must create an object of type HeadRules!");
    }
  }

  /**
   * Get the features which are implemented in each of the trainers extending
   * this class.
   *
   * @return the features
   */
  public final SequenceLabelerFactory getTaggerFactory() {
    return this.taggerFactory;
  }

  public final SequenceLabelerFactory setTaggerFactory(
      final SequenceLabelerFactory tokenNameFinderFactory) {
    this.taggerFactory = tokenNameFinderFactory;
    return this.taggerFactory;
  }

  /**
   * Get the features which are implemented in each of the trainers extending
   * this class.
   *
   * @return the features
   */
  public final SequenceLabelerFactory getChunkerFactory() {
    return this.chunkerFactory;
  }

  public final SequenceLabelerFactory setChunkerFactory(
      final SequenceLabelerFactory tokenNameFinderFactory) {
    this.chunkerFactory = tokenNameFinderFactory;
    return this.chunkerFactory;
  }

  /**
   * Get the features which are implemented in each of the trainers extending
   * this class.
   *
   * @return the features
   */
  public final ParserFactory getParserFactory() {
    return this.parserFactory;
  }

  public final ParserFactory setParserFactory(
      final ParserFactory parserFactory) {
    this.parserFactory = parserFactory;
    return parserFactory;
  }

  /**
   * Get the Sequence codec.
   *
   * @return the sequence codec
   */
  public final String getSequenceCodec() {
    String seqCodec = null;
    if ("BIO".equals(this.sequenceCodec)) {
      seqCodec = BioCodec.class.getName();
    } else if ("BILOU".equals(this.sequenceCodec)) {
      seqCodec = BilouCodec.class.getName();
    }
    return seqCodec;
  }

  /**
   * Set the sequence codec.
   *
   * @param aSeqCodec
   *          the sequence codec to be set
   */
  public final void setSequenceCodec(final String aSeqCodec) {
    this.sequenceCodec = aSeqCodec;
  }

}
