package io.floci.aws.services.comprehendmedical;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A rule-based reading of clinical text: a lexicon of conditions, medications, tests, procedures,
 * anatomy and social history, each term carrying its ICD-10-CM, RxNorm or SNOMED CT concept, plus
 * patterns for medication attributes (dosage, strength, frequency, route, form, duration), negation
 * cues and protected health information. Matching is case-insensitive, on word boundaries, and
 * prefers the longest term, so {@code type 2 diabetes} wins over {@code diabetes}.
 */
final class ComprehendMedicalAnalyzer {

    private ComprehendMedicalAnalyzer() {
    }

    /** A coded concept: ICD-10-CM, RxNorm or SNOMED CT. */
    record Concept(String code, String description) {}

    /** A lexicon term and what it means. */
    record Term(String category, String type, Concept icd10, Concept rxnorm, Concept snomed) {}

    /** A trait such as {@code NEGATION}, with its score. */
    record Trait(String name, double score) {}

    /** An entity found in the text; {@code attributes} are the entities that modify it. */
    record Entity(int id, String text, int begin, int end, String category, String type, double score,
                  List<Trait> traits, List<Entity> attributes, Term term, String relationshipType) {}

    private static final Map<String, Term> LEXICON = new LinkedHashMap<>();

    private static void condition(String text, String icdCode, String icdDescription, String snomedCode,
                                  String snomedDescription) {
        LEXICON.put(text, new Term("MEDICAL_CONDITION", "DX_NAME", new Concept(icdCode, icdDescription), null,
                new Concept(snomedCode, snomedDescription)));
    }

    private static void medication(String text, String type, String rxCode, String rxDescription) {
        LEXICON.put(text, new Term("MEDICATION", type, null, new Concept(rxCode, rxDescription), null));
    }

    private static void snomed(String text, String category, String type, String code, String description) {
        LEXICON.put(text, new Term(category, type, null, null, new Concept(code, description)));
    }

    private static void social(String text, String type) {
        LEXICON.put(text, new Term("BEHAVIORAL_ENVIRONMENTAL_SOCIAL", type, null, null, null));
    }

    static {
        condition("hypertension", "I10", "Essential (primary) hypertension", "38341003", "Hypertensive disorder, systemic arterial (disorder)");
        condition("high blood pressure", "I10", "Essential (primary) hypertension", "38341003", "Hypertensive disorder, systemic arterial (disorder)");
        condition("type 2 diabetes", "E11.9", "Type 2 diabetes mellitus without complications", "44054006", "Diabetes mellitus type 2 (disorder)");
        condition("type 2 diabetes mellitus", "E11.9", "Type 2 diabetes mellitus without complications", "44054006", "Diabetes mellitus type 2 (disorder)");
        condition("type 1 diabetes", "E10.9", "Type 1 diabetes mellitus without complications", "46635009", "Diabetes mellitus type 1 (disorder)");
        condition("diabetes", "E11.9", "Type 2 diabetes mellitus without complications", "73211009", "Diabetes mellitus (disorder)");
        condition("asthma", "J45.909", "Unspecified asthma, uncomplicated", "195967001", "Asthma (disorder)");
        condition("copd", "J44.9", "Chronic obstructive pulmonary disease, unspecified", "13645005", "Chronic obstructive lung disease (disorder)");
        condition("chronic obstructive pulmonary disease", "J44.9", "Chronic obstructive pulmonary disease, unspecified", "13645005", "Chronic obstructive lung disease (disorder)");
        condition("pneumonia", "J18.9", "Pneumonia, unspecified organism", "233604007", "Pneumonia (disorder)");
        condition("influenza", "J11.1", "Influenza due to unidentified influenza virus with other respiratory manifestations", "6142004", "Influenza (disorder)");
        condition("covid-19", "U07.1", "COVID-19", "840539006", "Disease caused by severe acute respiratory syndrome coronavirus 2 (disorder)");
        condition("fever", "R50.9", "Fever, unspecified", "386661006", "Fever (finding)");
        condition("cough", "R05.9", "Cough, unspecified", "49727002", "Cough (finding)");
        condition("headache", "R51.9", "Headache, unspecified", "25064002", "Headache (finding)");
        condition("migraine", "G43.909", "Migraine, unspecified, not intractable, without status migrainosus", "37796009", "Migraine (disorder)");
        condition("chest pain", "R07.9", "Chest pain, unspecified", "29857009", "Chest pain (finding)");
        condition("abdominal pain", "R10.9", "Unspecified abdominal pain", "21522001", "Abdominal pain (finding)");
        condition("back pain", "M54.9", "Dorsalgia, unspecified", "161891005", "Backache (finding)");
        condition("shortness of breath", "R06.02", "Shortness of breath", "267036007", "Dyspnea (finding)");
        condition("nausea", "R11.0", "Nausea", "422587007", "Nausea (finding)");
        condition("vomiting", "R11.10", "Vomiting, unspecified", "422400008", "Vomiting (disorder)");
        condition("diarrhea", "R19.7", "Diarrhea, unspecified", "62315008", "Diarrhea (finding)");
        condition("fatigue", "R53.83", "Other fatigue", "84229001", "Fatigue (finding)");
        condition("dizziness", "R42", "Dizziness and giddiness", "404640003", "Dizziness (finding)");
        condition("depression", "F32.A", "Depression, unspecified", "35489007", "Depressive disorder (disorder)");
        condition("anxiety", "F41.9", "Anxiety disorder, unspecified", "197480006", "Anxiety disorder (disorder)");
        condition("hyperlipidemia", "E78.5", "Hyperlipidemia, unspecified", "55822004", "Hyperlipidemia (disorder)");
        condition("high cholesterol", "E78.00", "Pure hypercholesterolemia, unspecified", "13644009", "Hypercholesterolemia (disorder)");
        condition("obesity", "E66.9", "Obesity, unspecified", "414916001", "Obesity (disorder)");
        condition("sepsis", "A41.9", "Sepsis, unspecified organism", "91302008", "Sepsis (disorder)");
        condition("stroke", "I63.9", "Cerebral infarction, unspecified", "230690007", "Cerebrovascular accident (disorder)");
        condition("heart failure", "I50.9", "Heart failure, unspecified", "84114007", "Heart failure (disorder)");
        condition("atrial fibrillation", "I48.91", "Unspecified atrial fibrillation", "49436004", "Atrial fibrillation (disorder)");
        condition("myocardial infarction", "I21.9", "Acute myocardial infarction, unspecified", "22298006", "Myocardial infarction (disorder)");
        condition("heart attack", "I21.9", "Acute myocardial infarction, unspecified", "22298006", "Myocardial infarction (disorder)");
        condition("chronic kidney disease", "N18.9", "Chronic kidney disease, unspecified", "709044004", "Chronic kidney disease (disorder)");
        condition("anemia", "D64.9", "Anemia, unspecified", "271737000", "Anemia (disorder)");
        condition("hypothyroidism", "E03.9", "Hypothyroidism, unspecified", "40930008", "Hypothyroidism (disorder)");
        condition("osteoarthritis", "M19.90", "Unspecified osteoarthritis, unspecified site", "396275006", "Osteoarthritis (disorder)");
        condition("urinary tract infection", "N39.0", "Urinary tract infection, site not specified", "68566005", "Urinary tract infectious disease (disorder)");
        condition("rash", "R21", "Rash and other nonspecific skin eruption", "271807003", "Eruption of skin (disorder)");

        medication("atenolol", "GENERIC_NAME", "1202", "atenolol");
        medication("metformin", "GENERIC_NAME", "6809", "metformin");
        medication("lisinopril", "GENERIC_NAME", "29046", "lisinopril");
        medication("aspirin", "GENERIC_NAME", "1191", "aspirin");
        medication("ibuprofen", "GENERIC_NAME", "5640", "ibuprofen");
        medication("acetaminophen", "GENERIC_NAME", "161", "acetaminophen");
        medication("amoxicillin", "GENERIC_NAME", "723", "amoxicillin");
        medication("atorvastatin", "GENERIC_NAME", "83367", "atorvastatin");
        medication("simvastatin", "GENERIC_NAME", "36567", "simvastatin");
        medication("omeprazole", "GENERIC_NAME", "7646", "omeprazole");
        medication("insulin", "GENERIC_NAME", "5856", "insulin");
        medication("warfarin", "GENERIC_NAME", "11289", "warfarin");
        medication("metoprolol", "GENERIC_NAME", "6918", "metoprolol");
        medication("amlodipine", "GENERIC_NAME", "17767", "amlodipine");
        medication("losartan", "GENERIC_NAME", "52175", "losartan");
        medication("hydrochlorothiazide", "GENERIC_NAME", "5487", "hydrochlorothiazide");
        medication("furosemide", "GENERIC_NAME", "4603", "furosemide");
        medication("levothyroxine", "GENERIC_NAME", "10582", "levothyroxine");
        medication("prednisone", "GENERIC_NAME", "8640", "prednisone");
        medication("albuterol", "GENERIC_NAME", "435", "albuterol");
        medication("gabapentin", "GENERIC_NAME", "25480", "gabapentin");
        medication("sertraline", "GENERIC_NAME", "36437", "sertraline");
        medication("azithromycin", "GENERIC_NAME", "18631", "azithromycin");
        medication("ciprofloxacin", "GENERIC_NAME", "2551", "ciprofloxacin");
        medication("clopidogrel", "GENERIC_NAME", "32968", "clopidogrel");
        medication("tylenol", "BRAND_NAME", "202433", "Tylenol");
        medication("advil", "BRAND_NAME", "153010", "Advil");
        medication("lipitor", "BRAND_NAME", "153165", "Lipitor");
        medication("glucophage", "BRAND_NAME", "151827", "Glucophage");
        medication("zoloft", "BRAND_NAME", "82728", "Zoloft");

        snomed("blood pressure", "TEST_TREATMENT_PROCEDURE", "TEST_NAME", "75367002", "Blood pressure (observable entity)");
        snomed("heart rate", "TEST_TREATMENT_PROCEDURE", "TEST_NAME", "364075005", "Heart rate (observable entity)");
        snomed("temperature", "TEST_TREATMENT_PROCEDURE", "TEST_NAME", "386725007", "Body temperature (observable entity)");
        snomed("hemoglobin a1c", "TEST_TREATMENT_PROCEDURE", "TEST_NAME", "43396009", "Hemoglobin A1c measurement (procedure)");
        snomed("a1c", "TEST_TREATMENT_PROCEDURE", "TEST_NAME", "43396009", "Hemoglobin A1c measurement (procedure)");
        snomed("blood glucose", "TEST_TREATMENT_PROCEDURE", "TEST_NAME", "33747003", "Glucose measurement, blood (procedure)");
        snomed("complete blood count", "TEST_TREATMENT_PROCEDURE", "TEST_NAME", "26604007", "Complete blood count (procedure)");
        snomed("x-ray", "TEST_TREATMENT_PROCEDURE", "TEST_NAME", "363680008", "Radiographic imaging procedure (procedure)");
        snomed("chest x-ray", "TEST_TREATMENT_PROCEDURE", "TEST_NAME", "399208008", "Plain chest X-ray (procedure)");
        snomed("mri", "TEST_TREATMENT_PROCEDURE", "TEST_NAME", "113091000", "Magnetic resonance imaging (procedure)");
        snomed("ct scan", "TEST_TREATMENT_PROCEDURE", "TEST_NAME", "77477000", "Computerized axial tomography (procedure)");
        snomed("ecg", "TEST_TREATMENT_PROCEDURE", "TEST_NAME", "29303009", "Electrocardiographic procedure (procedure)");
        snomed("ekg", "TEST_TREATMENT_PROCEDURE", "TEST_NAME", "29303009", "Electrocardiographic procedure (procedure)");
        snomed("biopsy", "TEST_TREATMENT_PROCEDURE", "PROCEDURE_NAME", "86273004", "Biopsy (procedure)");
        snomed("surgery", "TEST_TREATMENT_PROCEDURE", "PROCEDURE_NAME", "387713003", "Surgical procedure (procedure)");
        snomed("appendectomy", "TEST_TREATMENT_PROCEDURE", "PROCEDURE_NAME", "80146002", "Excision of appendix (procedure)");
        snomed("colonoscopy", "TEST_TREATMENT_PROCEDURE", "PROCEDURE_NAME", "73761001", "Colonoscopy (procedure)");
        snomed("chemotherapy", "TEST_TREATMENT_PROCEDURE", "TREATMENT_NAME", "367336001", "Chemotherapy (procedure)");
        snomed("physical therapy", "TEST_TREATMENT_PROCEDURE", "TREATMENT_NAME", "91251008", "Physical therapy procedure (regime/therapy)");
        snomed("dialysis", "TEST_TREATMENT_PROCEDURE", "TREATMENT_NAME", "108241001", "Dialysis procedure (procedure)");

        for (String[] part : new String[][]{{"chest", "51185008", "Thoracic structure (body structure)"},
                {"heart", "80891009", "Heart structure (body structure)"}, {"lung", "39607008", "Lung structure (body structure)"},
                {"lungs", "39607008", "Lung structure (body structure)"}, {"abdomen", "113345001", "Abdominal structure (body structure)"},
                {"head", "69536005", "Head structure (body structure)"}, {"knee", "72696002", "Knee region structure (body structure)"},
                {"arm", "53120007", "Upper limb structure (body structure)"}, {"leg", "30021000", "Lower leg structure (body structure)"},
                {"back", "77568009", "Structure of back of trunk (body structure)"}, {"kidney", "64033007", "Kidney structure (body structure)"},
                {"liver", "10200004", "Liver structure (body structure)"}, {"skin", "39937001", "Skin structure (body structure)"},
                {"throat", "49928004", "Structure of anterior portion of neck (body structure)"},
                {"stomach", "69695003", "Stomach structure (body structure)"}}) {
            snomed(part[0], "ANATOMY", "SYSTEM_ORGAN_SITE", part[1], part[2]);
        }
        social("smoker", "TOBACCO_USE");
        social("smokes", "TOBACCO_USE");
        social("tobacco", "TOBACCO_USE");
        social("alcohol", "ALCOHOL_CONSUMPTION");
        social("drinks alcohol", "ALCOHOL_CONSUMPTION");
        social("marijuana", "RECREATIONAL_DRUG_USE");
        social("allergic to penicillin", "ALLERGIES");
    }

    private static final Pattern WORD_START = Pattern.compile("(?i)\\b[a-z0-9]");
    private static final Pattern STRENGTH = Pattern.compile("(?i)\\b\\d+(?:\\.\\d+)?\\s?(?:mg|mcg|g|ml|units?|iu|%)\\b");
    private static final Pattern FREQUENCY = Pattern.compile("(?i)\\b(?:once|twice|three times|four times)\\s+(?:a|per)\\s+day\\b"
            + "|\\b(?:daily|nightly|weekly|bid|tid|qid|qd|qhs|prn|as needed|every \\d+ hours|every morning|every night|at bedtime)\\b");
    private static final Pattern ROUTE = Pattern.compile("(?i)\\b(?:by mouth|orally|oral|po|iv|intravenous(?:ly)?|subcutaneous(?:ly)?|topical(?:ly)?|inhaled|im)\\b");
    private static final Pattern FORM = Pattern.compile("(?i)\\b(?:tablets?|capsules?|injection|inhaler|cream|solution|syrup|patch)\\b");
    private static final Pattern DURATION = Pattern.compile("(?i)\\bfor\\s+\\d+\\s+(?:days?|weeks?|months?)\\b");
    private static final Pattern NEGATION = Pattern.compile("(?i)\\b(?:no|denies|denied|without|negative for|not|free of)\\b");

    private static final Pattern AGE = Pattern.compile("(?i)\\b(?:age[ds]?\\s+)?(\\d{1,3})(?:\\s?-?\\s?(?:year|yr)s?[- ]old\\b)?");
    private static final Pattern DATE = Pattern.compile("\\b\\d{4}-\\d{2}-\\d{2}\\b|\\b\\d{1,2}/\\d{1,2}/\\d{2,4}\\b"
            + "|\\b(?:Jan(?:uary)?|Feb(?:ruary)?|Mar(?:ch)?|Apr(?:il)?|May|June?|July?|Aug(?:ust)?|Sep(?:tember)?|Oct(?:ober)?"
            + "|Nov(?:ember)?|Dec(?:ember)?)\\.? \\d{1,2}(?:st|nd|rd|th)?,? \\d{4}\\b");
    private static final Pattern PHONE = Pattern.compile("\\(?\\b\\d{3}\\)?[-. ]\\d{3}[-. ]\\d{4}\\b");
    private static final Pattern EMAIL = Pattern.compile("\\b[\\w.+-]+@[\\w-]+\\.[\\w.-]+\\b");
    private static final Pattern URL = Pattern.compile("\\bhttps?://\\S+");
    private static final Pattern ID = Pattern.compile("(?i)\\b(?:MRN|SSN|ID|medical record number)[:#]?\\s*([A-Z0-9-]{4,})\\b");
    private static final Pattern FACILITY = Pattern.compile("\\b(?:[A-Z][a-z]+ )+(?:Clinic|Hospital|Medical Center|Health Center|Center)\\b");
    private static final Pattern STREET = Pattern.compile("\\b\\d{1,5} (?:[A-Z][a-z]+ )+(?:Street|St|Avenue|Ave|Road|Rd|Boulevard|Blvd|Lane|Ln|Drive|Dr)\\b\\.?");
    private static final Pattern PROFESSION = Pattern.compile("(?i)\\b(?:nurse|physician|surgeon|teacher|engineer|firefighter|pharmacist|lawyer|farmer|accountant)\\b");
    private static final Pattern TITLED_NAME = Pattern.compile("\\b(?:Mr|Mrs|Ms|Miss|Dr)\\.? ([A-Z][a-z]+(?: [A-Z][a-z]+)?)");
    private static final Pattern CAPITALIZED_PAIR = Pattern.compile("\\b([A-Z][a-z]+) ([A-Z][a-z]+)\\b");
    private static final Set<String> NOT_NAMES = Set.of("patient", "the", "he", "she", "this", "type", "seen", "presents",
            "history", "chief", "complaint", "diagnosis", "plan", "assessment", "medications", "allergies", "no", "on");

    /** Every entity in {@code text}: lexicon terms with their attributes, then PHI. */
    static List<Entity> entities(String text) {
        List<Entity> found = new ArrayList<>();
        int[] id = {0};
        String lower = text.toLowerCase(Locale.ROOT);
        boolean[] taken = new boolean[text.length() + 1];
        List<String> terms = new ArrayList<>(LEXICON.keySet());
        terms.sort(Comparator.comparingInt(String::length).reversed());
        for (String term : terms) {
            Matcher matcher = Pattern.compile("(?<![a-z0-9])" + Pattern.quote(term) + "(?![a-z0-9])").matcher(lower);
            while (matcher.find()) {
                if (overlaps(taken, matcher.start(), matcher.end())) {
                    continue;
                }
                mark(taken, matcher.start(), matcher.end());
                Term meaning = LEXICON.get(term);
                List<Trait> traits = new ArrayList<>();
                if ("MEDICAL_CONDITION".equals(meaning.category())) {
                    traits.add(new Trait(isSymptom(meaning) ? "SYMPTOM" : "DIAGNOSIS", 0.82));
                    if (negated(lower, matcher.start())) {
                        traits.add(new Trait("NEGATION", 0.95));
                    }
                }
                List<Entity> attributes = "MEDICATION".equals(meaning.category())
                        ? medicationAttributes(text, matcher.start(), matcher.end(), id) : List.of();
                found.add(new Entity(id[0]++, text.substring(matcher.start(), matcher.end()), matcher.start(),
                        matcher.end(), meaning.category(), meaning.type(), 0.97, traits, attributes, meaning, null));
            }
        }
        for (Entity phi : phi(text)) {
            if (!overlaps(taken, phi.begin(), phi.end())) {
                mark(taken, phi.begin(), phi.end());
                found.add(new Entity(id[0]++, phi.text(), phi.begin(), phi.end(), phi.category(), phi.type(),
                        phi.score(), List.of(), List.of(), null, null));
            }
        }
        found.sort(Comparator.comparingInt(Entity::begin));
        return found;
    }

    private static boolean isSymptom(Term term) {
        return term.icd10() != null && term.icd10().code().startsWith("R");
    }

    /** A negation cue within the four words before the term, in the same clause. */
    private static boolean negated(String lower, int start) {
        int clause = Math.max(Math.max(lower.lastIndexOf('.', start - 1), lower.lastIndexOf(';', start - 1)),
                lower.lastIndexOf(" but ", start - 1));
        String before = lower.substring(Math.max(clause + 1, 0), start);
        String[] words = before.trim().split("\\s+");
        String window = String.join(" ", Arrays.copyOfRange(words, Math.max(0, words.length - 4), words.length));
        return NEGATION.matcher(window).find();
    }

    /** Dosage, frequency, route, form and duration in the medication's clause. */
    private static List<Entity> medicationAttributes(String text, int begin, int end, int[] id) {
        int from = Math.max(0, Math.max(text.lastIndexOf('.', begin - 1), text.lastIndexOf(" and ", begin - 1)));
        int nextAnd = text.indexOf(" and ", end);
        int nextStop = text.indexOf('.', end);
        int to = Math.min(nextAnd < 0 ? text.length() : nextAnd, nextStop < 0 ? text.length() : nextStop);
        List<Entity> attributes = new ArrayList<>();
        attribute(text, STRENGTH, from, to, "DOSAGE", id, attributes);
        attribute(text, FREQUENCY, from, to, "FREQUENCY", id, attributes);
        attribute(text, ROUTE, from, to, "ROUTE_OR_MODE", id, attributes);
        attribute(text, FORM, from, to, "FORM", id, attributes);
        attribute(text, DURATION, from, to, "DURATION", id, attributes);
        return attributes;
    }

    private static void attribute(String text, Pattern pattern, int from, int to, String type, int[] id,
                                  List<Entity> into) {
        Matcher matcher = pattern.matcher(text);
        matcher.region(from, to);
        if (matcher.find()) {
            into.add(new Entity(id[0]++, matcher.group(), matcher.start(), matcher.end(), "MEDICATION", type, 0.95,
                    List.of(), List.of(), null, "DOSAGE".equals(type) ? "DOSAGE" : type));
        }
    }

    /** Protected health information: names, ages, dates, contact details, identifiers, addresses, professions. */
    static List<Entity> phi(String text) {
        List<Entity> found = new ArrayList<>();
        boolean[] taken = new boolean[text.length() + 1];
        add(found, taken, text, EMAIL, 0, "EMAIL");
        add(found, taken, text, URL, 0, "URL");
        add(found, taken, text, DATE, 0, "DATE");
        add(found, taken, text, PHONE, 0, "PHONE_OR_FAX");
        add(found, taken, text, ID, 1, "ID");
        add(found, taken, text, STREET, 0, "ADDRESS");
        add(found, taken, text, FACILITY, 0, "ADDRESS");
        add(found, taken, text, TITLED_NAME, 1, "NAME");
        Matcher pairs = CAPITALIZED_PAIR.matcher(text);
        while (pairs.find()) {
            String first = pairs.group(1).toLowerCase(Locale.ROOT);
            String second = pairs.group(2).toLowerCase(Locale.ROOT);
            if (NOT_NAMES.contains(first) || NOT_NAMES.contains(second) || LEXICON.containsKey(first)
                    || LEXICON.containsKey(second) || overlaps(taken, pairs.start(), pairs.end())) {
                continue;
            }
            mark(taken, pairs.start(), pairs.end());
            found.add(phiEntity(text, pairs.start(), pairs.end(), "NAME"));
        }
        Matcher ages = AGE.matcher(text);
        while (ages.find()) {
            boolean labelled = ages.group().toLowerCase(Locale.ROOT).startsWith("age")
                    || ages.group().toLowerCase(Locale.ROOT).contains("old");
            int value = Integer.parseInt(ages.group(1));
            if (labelled && value <= 125 && !overlaps(taken, ages.start(1), ages.end(1))) {
                mark(taken, ages.start(1), ages.end(1));
                found.add(phiEntity(text, ages.start(1), ages.end(1), "AGE"));
            }
        }
        add(found, taken, text, PROFESSION, 0, "PROFESSION");
        found.sort(Comparator.comparingInt(Entity::begin));
        return found;
    }

    private static void add(List<Entity> found, boolean[] taken, String text, Pattern pattern, int group,
                            String type) {
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            int start = matcher.start(group);
            int end = matcher.end(group);
            if (start >= 0 && !overlaps(taken, start, end)) {
                mark(taken, start, end);
                found.add(phiEntity(text, start, end, type));
            }
        }
    }

    private static Entity phiEntity(String text, int start, int end, String type) {
        return new Entity(0, text.substring(start, end), start, end, "PROTECTED_HEALTH_INFORMATION", type, 0.9,
                List.of(), List.of(), null, null);
    }

    private static boolean overlaps(boolean[] taken, int start, int end) {
        for (int i = start; i < end; i++) {
            if (taken[i]) {
                return true;
            }
        }
        return false;
    }

    private static void mark(boolean[] taken, int start, int end) {
        for (int i = start; i < end; i++) {
            taken[i] = true;
        }
    }

    /** Whether {@code text} has any word at all, for the empty-text check. */
    static boolean hasWords(String text) {
        return WORD_START.matcher(text).find();
    }
}
