import Foundation

struct SavedLink: Codable, Identifiable, Equatable {
    let id: UUID
    var title: String
    var urlString: String
    let dateAdded: Date

    init(
        id: UUID = UUID(),
        title: String,
        urlString: String,
        dateAdded: Date = Date()
    ) {
        self.id = id
        self.title = title
        self.urlString = urlString
        self.dateAdded = dateAdded
    }

    var url: URL? {
        URL(string: urlString)
    }

    var displayDomain: String {
        guard let host = url?.host?.lowercased(), !host.isEmpty else {
            return urlString
        }
        return host.hasPrefix("www.") ? String(host.dropFirst(4)) : host
    }
}

@MainActor
final class SavedLinksManager: ObservableObject {
    static let shared = SavedLinksManager()

    @Published private(set) var links: [SavedLink] = []

    private let storageKey = "saved_web_links"

    private init() {
        load()
    }

    static func normalizedURLString(_ raw: String) -> String {
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return "" }

        let lower = trimmed.lowercased()
        if lower.hasPrefix("http://") || lower.hasPrefix("https://") {
            return trimmed
        }
        if trimmed.hasPrefix("//") {
            return "https:" + trimmed
        }
        return "https://" + trimmed
    }

    static func validURL(from raw: String) -> URL? {
        let normalized = normalizedURLString(raw)
        guard !normalized.isEmpty,
              let url = URL(string: normalized),
              let scheme = url.scheme?.lowercased(),
              scheme == "http" || scheme == "https",
              let host = url.host,
              !host.isEmpty else {
            return nil
        }
        return url
    }

    @discardableResult
    func addLink(title: String, urlString: String) -> SavedLink? {
        guard let url = Self.validURL(from: urlString) else { return nil }
        let resolvedTitle = resolvedTitle(title, fallbackURL: url)
        let link = SavedLink(title: resolvedTitle, urlString: url.absoluteString)
        links.insert(link, at: 0)
        save()
        return link
    }

    @discardableResult
    func updateLink(id: UUID, title: String, urlString: String) -> SavedLink? {
        guard let index = links.firstIndex(where: { $0.id == id }),
              let url = Self.validURL(from: urlString) else {
            return nil
        }
        links[index].title = resolvedTitle(title, fallbackURL: url)
        links[index].urlString = url.absoluteString
        save()
        return links[index]
    }

    func deleteLink(id: UUID) {
        links.removeAll { $0.id == id }
        save()
    }

    func deleteLinks(at offsets: IndexSet) {
        for index in offsets.sorted(by: >) where links.indices.contains(index) {
            links.remove(at: index)
        }
        save()
    }

    private func resolvedTitle(_ rawTitle: String, fallbackURL: URL) -> String {
        let trimmed = rawTitle.trimmingCharacters(in: .whitespacesAndNewlines)
        if !trimmed.isEmpty {
            return trimmed
        }
        let host = fallbackURL.host?.lowercased() ?? fallbackURL.absoluteString
        return host.hasPrefix("www.") ? String(host.dropFirst(4)) : host
    }

    private func load() {
        guard let data = UserDefaults.standard.data(forKey: storageKey),
              let decoded = try? JSONDecoder().decode([SavedLink].self, from: data) else {
            links = []
            return
        }
        links = decoded
    }

    private func save() {
        guard let data = try? JSONEncoder().encode(links) else { return }
        UserDefaults.standard.set(data, forKey: storageKey)
    }
}
