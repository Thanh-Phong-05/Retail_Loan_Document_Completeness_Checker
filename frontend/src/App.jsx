
import {
  BrowserRouter,
  Navigate,
  Route,
  Routes,
  useParams,
} from "react-router-dom";

import { AuthProvider, useAuth } from "./context/AuthContext";
import { DossierProvider } from "./context/DossierContext";

import DashboardPage from "./pages/DashboardPage";
import LoginPage from "./pages/LoginPage";
import MyDossiersPage from "./pages/MyDossiersPage";
import ReviewWorkspacePage from "./pages/ReviewWorkspacePage";
import SelectPackagePage from "./pages/SelectPackagePage";
import UploadDossierPage from "./pages/UploadDossierPage";

// ======================================================
// PROTECTED ROUTE - KIEM TRA DANG NHAP VA PHAN QUYEN
// ======================================================

function RoleRoute({ allowedRoles, children }) {
  const { user, isAuthenticated } = useAuth();

  if (!isAuthenticated) {
    return <Navigate to="/login" replace />;
  }

  if (
    allowedRoles &&
    !allowedRoles.includes(user?.role)
  ) {
    return (
      <Navigate
        to={
          user?.role === "CUSTOMER"
            ? "/customer/select-package"
            : "/admin/dashboard"
        }
        replace
      />
    );
  }

  return children;
}

// ======================================================
// CHUYEN HUONG TRANG CHU THEO VAI TRO
// ======================================================

function HomeRedirect() {
  const { user, isAuthenticated } = useAuth();

  if (!isAuthenticated) {
    return <Navigate to="/login" replace />;
  }

  return (
    <Navigate
      to={
        user?.role === "CUSTOMER"
          ? "/customer/select-package"
          : "/admin/dashboard"
      }
      replace
    />
  );
}

// ======================================================
// SUB-206.2
// HO TRO DUONG DAN /dossiers/:id
//
// Vi ReviewWorkspacePage hien dang su dung
// useParams().applicationId, ta chuyen huong ve
// route cu cua nhom de tai su dung giao dien.
// ======================================================

function DossierRouteRedirect() {
  const { id } = useParams();

  if (!id) {
    return <Navigate to="/admin/queue" replace />;
  }

  return (
    <Navigate
      to={`/admin/review/${encodeURIComponent(id)}`}
      replace
    />
  );
}

// ======================================================
// APPLICATION ROUTES
// ======================================================

export default function App() {
  return (
    <AuthProvider>
      <DossierProvider>
        <BrowserRouter>
          <Routes>

            {/* TRANG CHU */}
            <Route
              path="/"
              element={<HomeRedirect />}
            />

            {/* DANG NHAP */}
            <Route
              path="/login"
              element={<LoginPage />}
            />

            {/* ====================================== */}
            {/* CUSTOMER ROUTES */}
            {/* ====================================== */}

            <Route
              path="/customer/select-package"
              element={
                <RoleRoute allowedRoles={["CUSTOMER"]}>
                  <SelectPackagePage />
                </RoleRoute>
              }
            />

            <Route
              path="/customer/upload"
              element={
                <RoleRoute allowedRoles={["CUSTOMER"]}>
                  <UploadDossierPage />
                </RoleRoute>
              }
            />

            <Route
              path="/customer/my-dossiers"
              element={
                <RoleRoute allowedRoles={["CUSTOMER"]}>
                  <MyDossiersPage />
                </RoleRoute>
              }
            />

            {/* ====================================== */}
            {/* BANK OFFICER ROUTES */}
            {/* ====================================== */}

            {/* DANH SACH HO SO */}
            <Route
              path="/admin/dashboard"
              element={
                <RoleRoute allowedRoles={["OFFICER"]}>
                  <DashboardPage queueOnly={false} />
                </RoleRoute>
              }
            />

            {/* HANG DOI XAC MINH */}
            <Route
              path="/admin/queue"
              element={
                <RoleRoute allowedRoles={["OFFICER"]}>
                  <DashboardPage queueOnly />
                </RoleRoute>
              }
            />

            {/* TAI HO SO MOI */}
            <Route
              path="/admin/upload"
              element={
                <RoleRoute allowedRoles={["OFFICER"]}>
                  <UploadDossierPage />
                </RoleRoute>
              }
            />

            {/* ====================================== */}
            {/* SUB-206.2 - REVIEW WORKSPACE */}
            {/* ====================================== */}

            {/* DUONG DAN HIEN TAI CUA NHOM */}
            <Route
              path="/admin/review/:applicationId"
              element={
                <RoleRoute allowedRoles={["OFFICER"]}>
                  <ReviewWorkspacePage />
                </RoleRoute>
              }
            />

            {/* DUONG DAN THEO DE BAI: /dossiers/:id */}
            <Route
              path="/dossiers/:id"
              element={
                <RoleRoute allowedRoles={["OFFICER"]}>
                  <DossierRouteRedirect />
                </RoleRoute>
              }
            />

            {/* ====================================== */}
            {/* FALLBACK */}
            {/* ====================================== */}

            <Route
              path="*"
              element={<HomeRedirect />}
            />

          </Routes>
        </BrowserRouter>
      </DossierProvider>
    </AuthProvider>
  );
}
