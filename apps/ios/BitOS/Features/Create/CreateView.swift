import SwiftUI

struct CreateView: View {
    let businessCore: any BusinessCoreClient
    @State private var status = "Draft ready"

    var body: some View {
        NavigationStack {
            List {
                Section("Quick create") {
                    Label("Record Bitz", systemImage: "camera.fill")
                    Label("Import media", systemImage: "photo.on.rectangle")
                    Label("Quick MEM", systemImage: "face.smiling.inverse")
                }
                Section("Studio") {
                    Label("Project library", systemImage: "square.stack.3d.up.fill")
                    Label("Mass production", systemImage: "rectangle.3.group.fill")
                    Text(status)
                        .foregroundStyle(.secondary)
                }
            }
            .navigationTitle("Create")
            .task {
                status = await businessCore.publishStatusLabel()
            }
        }
    }
}
