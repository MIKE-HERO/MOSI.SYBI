# Simplify Patient Registration and Database Schema

This plan aims to simplify the patient registration process by reducing the number of fields, adding a search-and-autocomplete feature from the API, and streamlining the database schema to match the API's simplified address structure.

## User Review Required

> [!IMPORTANT]
> - The database schema for `Paciente` will be changed destructively. All local patient data will be cleared because `fallbackToDestructiveMigration()` is enabled.
> - The registration form will no longer have separate fields for street, suburb, city, etc. Instead, it will use a single "Dirección" field.
> - Search will be performed by Phone or CURP from the same input field.

## Proposed Changes

### Database Layer

#### [MODIFY] [Paciente.kt](file:///C:/Users/1000CXSTAS01/AndroidStudioProjects/Quiosco/app/src/main/java/com/example/quiosco/database/Paciente.kt)
- Remove separate address fields: `cp`, `estado`, `municipio`, `colonia`, `calle`, `numero`.
- Add a single `direccion: String` field.
- Simplify phone fields to a single `telefono: String`.
- Remove `sexo_curp`, `pais_nacimiento`, `entidad_nacimiento` if they are not strictly required for the new flow (re-evaluating if they should stay for CURP calculation).

#### [MODIFY] [PacienteDao.kt](file:///C:/Users/1000CXSTAS01/AndroidStudioProjects/Quiosco/app/src/main/java/com/example/quiosco/database/PacienteDao.kt)
- Update `obtenerPacientePorTelefono` to use the new `telefono` field name.

#### [MODIFY] [AppDatabase.kt](file:///C:/Users/1000CXSTAS01/AndroidStudioProjects/Quiosco/app/src/main/java/com/example/quiosco/database/AppDatabase.kt)
- Increment database version to trigger destructive migration.

---

### Registration Screen

#### [MODIFY] [activity_register.xml](file:///C:/Users/1000CXSTAS01/AndroidStudioProjects/Quiosco/app/src/main/res/layout/activity_register.xml)
- Add a search section at the top of the form with an `EditText` for Phone/CURP and a "Search" `Button`.
- Remove separate address inputs and replace with a single `etRegDireccion`.
- Remove `etRegTelefonoFijo` and simplify to `etRegTelefono`.
- Remove Entidad Nacimiento, Pais Nacimiento, and Sexo CURP from the form to simplify as requested.

#### [MODIFY] [RegisterActivity.kt](file:///C:/Users/1000CXSTAS01/AndroidStudioProjects/Quiosco/app/src/main/java/com/example/quiosco/RegisterActivity.kt)
- Implement `buscarPaciente()` logic using `PacienteRemoteRepository`.
- Implement `autocompletarCampos(UsuarioWeb)` to populate the form from API results.
- Update `guardarPaciente()` to collect data from the simplified form and save to the new `Paciente` model.
- Remove CP lookup logic and spinner setups for address fields.

---

### Patient Table Screen

#### [MODIFY] [activity_patient_table.xml](file:///C:/Users/1000CXSTAS01/AndroidStudioProjects/Quiosco/app/src/main/res/layout/activity_patient_table.xml)
- Update headers to reflect the simplified fields.

#### [MODIFY] [PatientTableActivity.kt](file:///C:/Users/1000CXSTAS01/AndroidStudioProjects/Quiosco/app/src/main/java/com/example/quiosco/PatientTableActivity.kt)
- Update `createPatientRow()` to display the new fields.
- Update `mostrarDetallePaciente()` (if it exists as a dialog/screen) to show the simplified data.
- Update `saveAllChanges()` to work with the new schema.

---

### Other Impacts
- `FaceLoginActivity.kt`, `PhoneLoginActivity.kt`, `ResultsActivity.kt`: Any reference to `telefono_celular` must be updated to `telefono`.
- `MeasurementActivity.kt`: Update references to patient fields.

## Verification Plan

### Automated Tests
- Build the project to ensure no compilation errors.
- `app:assembleDebug`

### Manual Verification
- Open the Registration screen.
- Perform a search by Phone and verify fields are autocompleted.
- Perform a search by CURP and verify fields are autocompleted.
- Complete the form manually and save a new patient.
- Verify the patient appears correctly in the "Gestión de Pacientes" table.
- Verify that the full address is displayed in a single column in the table.
