import { Routes, Route } from "react-router-dom";
import JobsBoard from "./pages/JobsBoard";
import JobDetailsPage from "./pages/JobDetailsPage";
import SubmitJobPage from "./pages/SubmitJobPage";
import LandingPage from "./pages/LandingPage";
import "./App.css";

export default function App() {
  return (
    <Routes>
      <Route path="/" element={<LandingPage />} />
      <Route path="/jobs" element={<JobsBoard />} />
      <Route path="/jobs/:jobId" element={<JobDetailsPage />} />
      <Route path="/submit" element={<SubmitJobPage />} />
    </Routes>
  );
}
