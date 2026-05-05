import { useEffect, useState } from "react";
import { getMockIdentity, setMockIdentity, subscribeToMockIdentityChanges } from "../api/mock_identity";

export default function MockIdentityPanel() {
  const [identity, setIdentity] = useState(getMockIdentity);

  useEffect(() => subscribeToMockIdentityChanges(setIdentity), []);

  function handleChange(field, value) {
    setIdentity(setMockIdentity({ ...identity, [field]: value }));
  }

  return (
    <section className="mock-identity-card" aria-label="Mock identity">
      <div className="form-group">
        <label className="label" htmlFor="mock-institution">
          Institution
        </label>
        <input
          id="mock-institution"
          className="input"
          value={identity.institution}
          onChange={(event) => handleChange("institution", event.target.value)}
        />
      </div>
      <div className="form-group">
        <label className="label" htmlFor="mock-user">
          User
        </label>
        <input
          id="mock-user"
          className="input"
          value={identity.user}
          onChange={(event) => handleChange("user", event.target.value)}
        />
      </div>
    </section>
  );
}
