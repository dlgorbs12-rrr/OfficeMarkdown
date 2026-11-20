import java.math.BigInteger;
import java.nio.file.Path;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.*;
import java.util.regex.MatchResult;
import java.util.regex.Pattern;

final class LatestVersionSelection {
    record Choice(Path latest, List<Path> skipped) {}
    record Preview(long discovered, long latestSkipped, int ambiguousGroups, List<Choice> choices) {
        long selected() { return discovered - latestSkipped; }
    }
    record Plan(Preview preview, Set<Path> skippedPaths) {}

    private static final String SEPARATOR = "[\\s_-]+";
    private static final Pattern VERSION = Pattern.compile(SEPARATOR
            + "(?:version|ver|v|버전)[\\s._-]*([0-9][0-9.a-z]*)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern VERSION_NUMBER = Pattern.compile("[0-9]+(?:\\.[0-9]+)*");
    private static final Pattern DATE = Pattern.compile(SEPARATOR
            + "([0-9]{4})([-._]?)([0-9]{2})\\2([0-9]{2})(?:T([0-9]{2})([0-9]{2})([0-9]{2}))?$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern SHORT_DATE = Pattern.compile(SEPARATOR + "([0-9]{2})([0-9]{2})([0-9]{2})$");

    private record Key(Path parent, String extension, String stem) {}
    private record Date(LocalDateTime value, boolean hasTime) {}
    private record Revision(String stem, List<BigInteger> version, Date date, boolean ambiguous) {}

    private static final class Member {
        final Revision revision;
        long count = 1;

        Member(Revision revision) { this.revision = revision; }
    }

    private static final class Group {
        final Map<Path, Member> members = new LinkedHashMap<>();
        boolean hasUnmarkedFile;
    }

    private final Map<Key, Group> groups = new LinkedHashMap<>();
    private final Set<Path> explicitFiles = new HashSet<>();
    private final Set<Path> incompleteFolders = new HashSet<>();

    void record(Path path, boolean fromFolder) {
        if (!fromFolder) {
            explicitFiles.add(path);
            return;
        }
        Key name = name(path);
        Revision revision = revision(name.stem());
        if (revision == null) return;
        Key key = new Key(name.parent(), name.extension(), revision.stem());
        Group group = groups.computeIfAbsent(key, ignored -> new Group());
        Member existing = group.members.putIfAbsent(path, new Member(revision));
        if (existing != null) existing.count++;
    }

    boolean hasGroups() { return !groups.isEmpty(); }

    void markIncomplete(Path directory) { incompleteFolders.add(directory); }

    void checkUnmarked(Path path) {
        Group group = groups.get(name(path));
        if (group != null) group.hasUnmarkedFile = true;
    }

    Plan plan(long discovered) {
        var skippedPaths = new HashSet<Path>();
        var choices = new ArrayList<Choice>();
        long skipped = 0;
        int ambiguous = 0;
        for (var entry : groups.entrySet()) {
            Group group = entry.getValue();
            if (group.members.size() < 2 && !group.hasUnmarkedFile) continue;
            var latest = group.hasUnmarkedFile || incompleteFolders.contains(entry.getKey().parent())
                    ? null : findLatest(group);
            if (latest == null) {
                ambiguous++;
                continue;
            }
            var older = new ArrayList<Path>();
            for (var member : group.members.entrySet()) {
                Path candidate = member.getKey();
                if (candidate.equals(latest) || explicitFiles.contains(candidate)) continue;
                skippedPaths.add(candidate);
                skipped += member.getValue().count;
                older.add(candidate);
            }
            if (!older.isEmpty()) choices.add(new Choice(latest, List.copyOf(older)));
        }
        return new Plan(new Preview(discovered, skipped, ambiguous, List.copyOf(choices)), skippedPaths);
    }

    private static Path findLatest(Group group) {
        var members = new ArrayList<>(group.members.entrySet());
        Revision first = members.getFirst().getValue().revision;
        var timePrecision = new HashMap<LocalDate, Boolean>();
        for (var entry : members) {
            Revision revision = entry.getValue().revision;
            if (revision.ambiguous() || (revision.version() == null) != (first.version() == null)
                    || (revision.date() == null) != (first.date() == null)) return null;
            Date date = revision.date();
            if (date != null) {
                Boolean previous = timePrecision.putIfAbsent(date.value().toLocalDate(), date.hasTime());
                if (previous != null && previous != date.hasTime()) return null;
            }
        }

        // Ordering by version and then date exposes opposite ordering in either property.
        members.sort((left, right) -> {
            Revision a = left.getValue().revision;
            Revision b = right.getValue().revision;
            int version = compareVersions(a, b);
            return version != 0 ? version : compareDates(a, b);
        });
        for (int i = 1; i < members.size(); i++) {
            Revision previous = members.get(i - 1).getValue().revision;
            Revision current = members.get(i).getValue().revision;
            int date = compareDates(previous, current);
            if (date > 0 || (date == 0 && compareVersions(previous, current) == 0)) return null;
        }
        return members.getLast().getKey();
    }

    private static int compareVersions(Revision a, Revision b) {
        if (a.version() == null) return 0;
        int count = Math.max(a.version().size(), b.version().size());
        for (int i = 0; i < count; i++) {
            BigInteger left = i < a.version().size() ? a.version().get(i) : BigInteger.ZERO;
            BigInteger right = i < b.version().size() ? b.version().get(i) : BigInteger.ZERO;
            int version = left.compareTo(right);
            if (version != 0) return version;
        }
        return 0;
    }

    private static int compareDates(Revision a, Revision b) {
        return a.date() == null ? 0 : a.date().value().compareTo(b.date().value());
    }

    private static Key name(Path path) {
        String filename = path.getFileName().toString();
        int dot = filename.lastIndexOf('.');
        return new Key(path.getParent(), filename.substring(dot + 1).toLowerCase(Locale.ROOT),
                filename.substring(0, dot).toLowerCase(Locale.ROOT));
    }

    private static Revision revision(String name) {
        String stem = name;
        List<BigInteger> version = null;
        Date date = null;
        boolean versionMarked = false;
        boolean dateMarked = false;
        boolean shortDateUsed = false;
        boolean ambiguous = false;
        while (true) {
            var versionMatch = VERSION.matcher(stem);
            var dateMatch = DATE.matcher(stem);
            var shortDateMatch = SHORT_DATE.matcher(stem);
            if (versionMatch.find()) {
                String number = versionMatch.group(1);
                if (versionMarked || !VERSION_NUMBER.matcher(number).matches()) ambiguous = true;
                else version = Arrays.stream(number.split("\\.")).map(BigInteger::new).toList();
                versionMarked = true;
                stem = stem.substring(0, versionMatch.start());
            } else if (dateMatch.find()) {
                date = date(dateMatch, false);
                if (dateMarked || date == null) ambiguous = true;
                dateMarked = true;
                stem = stem.substring(0, dateMatch.start());
            } else if (shortDateMatch.find()) {
                date = date(shortDateMatch, true);
                if (dateMarked || date == null) ambiguous = true;
                dateMarked = true;
                shortDateUsed = true;
                stem = stem.substring(0, shortDateMatch.start());
            } else {
                break;
            }
        }
        if (stem.isBlank() || (!versionMarked && (!dateMarked || shortDateUsed))) return null;
        return new Revision(stem, version, date, ambiguous);
    }

    private static Date date(MatchResult match, boolean shortDate) {
        try {
            int year = number(match, 1);
            if (shortDate) year += year >= 70 ? 1900 : 2000;
            if (year < 1) return null;
            LocalDate day = LocalDate.of(year, number(match, shortDate ? 2 : 3), number(match, shortDate ? 3 : 4));
            boolean hasTime = !shortDate && match.group(5) != null;
            LocalTime time = hasTime ? LocalTime.of(number(match, 5), number(match, 6), number(match, 7)) : LocalTime.MIDNIGHT;
            return new Date(day.atTime(time), hasTime);
        } catch (DateTimeException invalid) {
            return null;
        }
    }

    private static int number(MatchResult match, int group) { return Integer.parseInt(match.group(group)); }
}
