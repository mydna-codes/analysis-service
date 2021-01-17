package codes.mydna.services.impl;

import codes.mydna.auth.common.models.User;
import codes.mydna.clients.grpc.AnalysisResultGrpcClient;
import codes.mydna.clients.grpc.DnaServiceGrpcClient;
import codes.mydna.clients.grpc.EnzymeServiceGrpcClient;
import codes.mydna.clients.grpc.GeneServiceGrpcClient;
import codes.mydna.clients.grpc.models.CheckedEntity;
import codes.mydna.clients.kafka.KafkaLargeScaleAnalysisClient;
import codes.mydna.lib.*;
import codes.mydna.lib.enums.Orientation;
import codes.mydna.lib.enums.SequenceType;
import codes.mydna.lib.enums.Status;
import codes.mydna.lib.util.BasePairUtil;
import codes.mydna.services.AnalysisService;
import codes.mydna.validation.Assert;

import javax.annotation.PostConstruct;
import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

@ApplicationScoped
public class AnalysisServiceImpl implements AnalysisService {

    private static final Logger LOG = Logger.getLogger(AnalysisServiceImpl.class.getName());

    @PostConstruct
    private void postConstruct() {
        LOG.info(AnalysisServiceImpl.class.getSimpleName() + " initialized");
    }

    @Inject
    private DnaServiceGrpcClient dnaServiceGrpcClient;

    @Inject
    private EnzymeServiceGrpcClient enzymeServiceGrpcClient;

    @Inject
    private GeneServiceGrpcClient geneServiceGrpcClient;

    @Inject
    private AnalysisResultGrpcClient analysisResultGrpcClient;

    @Inject
    private KafkaLargeScaleAnalysisClient largeScaleAnalysisClient;


    @Override
    public AnalysisResult analyze(AnalysisRequest request, User user) {

        AnalysisResult result = new AnalysisResult();

        Assert.fieldNotEmpty(request.getDnaId(), "dnaId");

        // Initialize total execution timer
        long totalExecTimer = System.currentTimeMillis();

        CheckedEntity<Dna> receivedDna = dnaServiceGrpcClient.getDna(request.getDnaId(), user);
        result.setStatus(receivedDna.getStatus());


        // Dna sequence response validation
        if (receivedDna.getStatus() != Status.OK) {

            // LARGE_SCALE -> Send request to large scale analysis service
            if(receivedDna.getStatus() == Status.LARGE_SCALE)
                LOG.info("Redirecting request to large scale service...");
                largeScaleAnalysisClient.runLargeScaleAnalysis(request, user);

            return result;
        }

        LOG.info("Analyzing DNA with id: " + receivedDna.getEntity().getId());

        result.setDna(receivedDna.getEntity());
        String sequence = receivedDna.getEntity().getSequence().getValue();

        String name = request.getAnalysisName();
        result.setAnalysisName((name == null || name.isEmpty())
                ? "Analysis of '" + result.getDna().getName() + "' DNA sequence"
                : name);

        // Start analysis timer
        long analysisTimer = System.currentTimeMillis();

        LOG.info("Requesting enzymes...");
        List<Enzyme> receivedEnzymes = enzymeServiceGrpcClient.getMultipleEnzymes(request.getEnzymeIds(), user);
        result.setEnzymes(findEnzymes(sequence, receivedEnzymes));
        LOG.info("Enzymes received: " + receivedEnzymes.size());
        LOG.info("Requesting genes...");
        List<Gene> receivedGenes = geneServiceGrpcClient.getMultipleGenes(request.getGeneIds(), user);
        result.setGenes(findGenes(sequence, receivedGenes));
        LOG.info("Genes received: " + receivedGenes.size());

        // Stop timers
        result.setAnalysisExecutionTime((int) (System.currentTimeMillis() - analysisTimer));
        result.setTotalExecutionTime((int) (System.currentTimeMillis() - totalExecTimer));

        if(user != null) {
            LOG.info("Inserting analysis result...");
            return analysisResultGrpcClient.insertAnalysisResult(result, user);
        }

        result.setStatus(Status.UNSAVED);
        return result;
    }

    private List<FoundEnzyme> findEnzymes(String sequence, List<Enzyme> enzymes) {

        List<FoundEnzyme> foundEnzymes = new ArrayList<>();

        if (enzymes == null)
            return foundEnzymes;

        for (Enzyme enzyme : enzymes) {

            // Get enzyme sequence as a string
            String enzymeSequence = enzyme.getSequence().getValue();

            // Find all cut indexes for provided enzyme
            List<Integer> indexes = BasePairUtil.findAll(sequence, enzymeSequence, SequenceType.ENZYME);

            // Create cuts for all found indexes
            List<EnzymeCut> enzymeCuts = new ArrayList<>();
            for (int i : indexes) {

                // Calculate real cuts
                EnzymeCut cut = new EnzymeCut();
                cut.setUpperCut(i + enzyme.getUpperCut());
                cut.setLowerCut(i + enzyme.getLowerCut());
                enzymeCuts.add(cut);
            }

            // Skip adding redundant genes
            if (enzymeCuts.isEmpty())
                continue;

            // Create new searchedEnzyme
            FoundEnzyme foundEnzyme = new FoundEnzyme();
            foundEnzyme.setEnzyme(enzyme);
            foundEnzyme.setCuts(enzymeCuts);

            // Add enzyme to the list
            foundEnzymes.add(foundEnzyme);
        }
        return foundEnzymes;
    }

    private List<FoundGene> findGenes(String sequence, List<Gene> genes) {

        List<FoundGene> foundGenes = new ArrayList<>();

        if (genes == null)
            return foundGenes;

        for (Gene gene : genes) {

            // Get gene sequence as a string
            String geneSequence = gene.getSequence().getValue();

            // Find all overlap indexes for provided gene
            List<Integer> indexes = BasePairUtil.findAll(sequence, geneSequence, SequenceType.GENE);

            // Create overlaps for all found indexes
            List<GeneOverlap> geneOverlaps = new ArrayList<>();
            for (int i : indexes) {

                // Calculate real cuts
                GeneOverlap overlap = new GeneOverlap();
                overlap.setFromIndex(i);
                overlap.setToIndex(i + geneSequence.length());
                overlap.setOrientation(Orientation.POSITIVE);
                geneOverlaps.add(overlap);
            }

            // Reverse sequence
            String geneReverseSequence = BasePairUtil.reverse(geneSequence);

            // Find all overlap indexes for provided gene for reverseSequence
            indexes = BasePairUtil.findAll(sequence, geneReverseSequence, SequenceType.GENE);

            // Add overlaps for all found indexes for reverse sequence
            for (int i : indexes) {

                // Calculate real cuts
                GeneOverlap overlap = new GeneOverlap();
                overlap.setFromIndex(i + geneSequence.length()); // From index is higher for reverse sequences
                overlap.setToIndex(i);
                overlap.setOrientation(Orientation.NEGATIVE);
                geneOverlaps.add(overlap);
            }

            // Skip adding redundant genes
            if (geneOverlaps.isEmpty())
                continue;

            // Create new searchedEnzyme
            FoundGene foundGene = new FoundGene();
            foundGene.setGene(gene);
            foundGene.setOverlaps(geneOverlaps);

            // Add gene to the list
            foundGenes.add(foundGene);
        }
        return foundGenes;
    }


}
